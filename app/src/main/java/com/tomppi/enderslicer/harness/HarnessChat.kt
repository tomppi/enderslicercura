package com.tomppi.enderslicer.harness

import org.json.JSONArray
import org.json.JSONObject

/**
 * A conversation with the harness, carried over the session-list projection.
 *
 * The reply does not come back from [HarnessClient.prompt] - that only reports
 * acceptance. Full streaming needs the `/api/remote.mux` WebSocket, but the
 * session list already projects every turn as a prompt/response pair, so a
 * prompt followed by a short poll of that projection is enough to hold a
 * conversation without implementing the streaming protocol.
 *
 * The projection is a **preview**: the harness truncates each side to roughly a
 * hundred characters. That is the deliberate trade here - a working assistant
 * today, at the cost of reading replies as snippets until the stream is built.
 */
class HarnessChat(
    private val client: HarnessClient,
    /**
     * Working directory new sessions are rooted at, on the harness host.
     *
     * Every session this chat creates is rooted here, including the ones made
     * on the way to [send] and [ensureSession]. That matters because
     * `session/create` without a `cwd` lands in the *harness process's own*
     * directory instead, which is not where the skills live - a session created
     * that way cannot load them however clearly the prompt names them, and the
     * failure is silent.
     */
    private val workspace: String = "",
) {

    /** The session this conversation runs in, resolved on first use. */
    var sessionId: String? = null
        private set

    /** What [connect] decided, so the caller can tell the user. */
    data class Adopted(val sessionId: String, val reused: Boolean)

    /**
     * Adopts [storedSessionId] when the harness still has it, and otherwise
     * creates a session rooted at [workspace].
     *
     * The existence check is the point. `session/prompt` accepts an id the
     * harness has never heard of and then answers nothing, so a stale stored id
     * gives a chat that looks connected and stays silent forever. Checking also
     * makes the replacement deliberate rather than a session quietly appearing
     * whenever the stored id happens to be empty.
     */
    fun connect(storedSessionId: String = ""): Adopted {
        val known = storedSessionId.takeIf(String::isNotBlank)?.takeIf { stateOf(it).exists }
        if (known != null) {
            sessionId = known
            return Adopted(known, reused = true)
        }
        return Adopted(create(), reused = false)
    }

    /**
     * The session to talk in, created on first use and then reused.
     *
     * A session is created once and then reused, so the conversation survives
     * the app being restarted as long as the harness keeps it.
     */
    fun ensureSession(): String = sessionId ?: create()

    private fun create(): String {
        val created = client.createSession(workspace.takeIf(String::isNotBlank))
        sessionId = created
        return created
    }

    /**
     * Sends [text] into the session, with [receiptId] attached when there is
     * one.
     *
     * The receipt comes from [attach] and **must** be passed on: uploading
     * stages the bytes against the session, but nothing reaches the agent until
     * a prompt names the receipt. Sending the text alone leaves the agent with
     * a message that merely mentions an image it was never given.
     */
    fun send(text: String, receiptId: String? = null): String {
        val session = ensureSession()
        client.prompt(session, text, receiptId)
        return session
    }

    /**
     * Stops the conversation.
     *
     * `session/cancel` stops the *agent*, never the processes it started over
     * ssh, and a background job that finishes afterwards delivers a notice that
     * starts a fresh turn - so a bare cancel is undone a minute later and the
     * work carries on. The follow-up instruction is what actually ends it: it
     * runs once the cancelled turn unwinds and tells the agent to drop its
     * jobs.
     */
    fun stop() {
        val session = sessionId ?: return
        client.cancelSession(session)
        client.prompt(session, STOP_INSTRUCTION)
    }

    /**
     * Stages an image against the session and returns the receipt.
     *
     * Uploading and prompting are separate calls on purpose: the harness stages
     * the bytes first and the prompt then refers to them, so a prompt cannot
     * arrive before the image it is talking about.
     */
    fun attach(sessionId: String, name: String, bytes: ByteArray): String =
        client.uploadFile(sessionId, name, bytes).optString("receiptId")

    /** What the harness currently projects for one conversation. */
    data class SessionState(
        val sessionId: String,
        /** False when the harness has no such session at all. */
        val exists: Boolean,
        /** True while a turn is in flight - queued or executing. */
        val running: Boolean,
        /** Oldest first. An in-flight turn has a prompt and no response yet. */
        val turns: List<Turn>,
        /** Inclusive log cut, which [HarnessClient.pageMessages] needs. */
        val asOfSeq: Long = 0L,
    ) {
        /** The newest turn has a reply. */
        val answered: Boolean get() = turns.lastOrNull()?.isAnswered == true
    }

    /** The harness's current view of this session. */
    fun state(): SessionState = stateOf(sessionId ?: "")

    /**
     * The conversation so far, oldest first.
     */
    fun turns(): List<Turn> = state().turns

    /**
     * The conversation with each answer at full length.
     *
     * The list projection clips every turn to about a hundred characters, so a
     * reply that took the agent a page to write arrives as its first line and
     * an ellipsis. This reads the log instead and falls back to the preview
     * only when the log cannot be read or does not reach back far enough.
     */
    fun messages(maxMessages: Int = MESSAGE_PAGE_SIZE): List<ChatMessage> {
        val state = state()
        if (!state.exists) return emptyList()
        val full = fullResponses(state, maxMessages)
        return buildList {
            state.turns.forEachIndexed { index, turn ->
                if (turn.prompt.isNotBlank()) add(ChatMessage(fromUser = true, text = turn.prompt))
                // Only answered turns get a reply line, so an in-flight turn
                // does not show half a step's narration as if it were the
                // answer.
                if (!turn.isAnswered) return@forEachIndexed
                val text = full[index + 1] ?: turn.response
                if (text.isNotBlank()) add(ChatMessage(fromUser = false, text = text))
            }
        }
    }

    private fun fullResponses(state: SessionState, maxMessages: Int): Map<Int, String> = try {
        fullResponsesOf(client.pageMessages(state.sessionId, state.asOfSeq, maxMessages))
    } catch (error: Exception) {
        // A chat that can only show previews is still a chat. Losing the whole
        // conversation because one log read failed would be worse.
        emptyMap()
    }

    private fun stateOf(id: String): SessionState = stateOf(client.listSessions(), id)

    companion object {
        /** Messages pulled back from the log when a reply is rendered in full. */
        const val MESSAGE_PAGE_SIZE = 40

        /**
         * The closing text of each turn, keyed by turn number.
         *
         * Two filters, both load-bearing:
         *
         *  - Only `text` blocks count. An assistant message also carries
         *    `reasoning` blocks, which are the agent's private working, and
         *    concatenating everything would put its thinking in the chat.
         *  - Later messages for a turn replace earlier ones, so what surfaces is
         *    the turn's final answer rather than its intermediate narration.
         */
        fun fullResponsesOf(page: JSONObject): Map<Int, String> {
            val records = page.optJSONArray("records") ?: return emptyMap()
            val byTurn = HashMap<Int, String>()
            for (index in 0 until records.length()) {
                val event = records.optJSONObject(index)?.optJSONObject("event") ?: continue
                if (event.optString("type") != "assistant/message") continue
                val data = event.optJSONObject("data") ?: continue
                val text = textOf(data.optJSONObject("message")?.optJSONArray("content"))
                if (text.isNotBlank()) byTurn[data.optInt("turn")] = text
            }
            return byTurn
        }

        /** Concatenates the `text` blocks of one message, skipping the rest. */
        private fun textOf(content: JSONArray?): String {
            if (content == null) return ""
            val parts = ArrayList<String>(content.length())
            for (index in 0 until content.length()) {
                val block = content.optJSONObject(index) ?: continue
                if (block.optString("type") != "text") continue
                val text = block.optString("text")
                if (text.isNotBlank()) parts += text
            }
            return parts.joinToString("\n\n")
        }

        /**
         * Reads one session out of a `session/list` response.
         *
         * Matches on the item's own `sessionId` field rather than searching the
         * document for the string: ids also appear inside other sessions' turn
         * text, and that search finds the mention rather than the session.
         */
        fun stateOf(list: JSONObject, sessionId: String): SessionState {
            val items = list.optJSONArray("items") ?: return missing(sessionId)
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                if (item.optString("sessionId") != sessionId) continue
                return SessionState(
                    sessionId = sessionId,
                    exists = true,
                    running = item.optBoolean("running"),
                    turns = turnsOf(item),
                    asOfSeq = item.optJSONObject("projections")?.optLong("asOfSeq") ?: 0L,
                )
            }
            return missing(sessionId)
        }

        private fun missing(sessionId: String) = SessionState(
            sessionId = sessionId,
            exists = false,
            running = false,
            turns = emptyList(),
        )

        private fun turnsOf(item: JSONObject): List<Turn> {
            val outline = item
                .optJSONObject("projections")
                ?.optJSONObject("values")
                ?.optJSONArray("turnOutline")
                ?: return emptyList()
            val result = ArrayList<Turn>(outline.length())
            for (index in 0 until outline.length()) {
                val turn = outline.optJSONObject(index) ?: continue
                result += Turn(
                    prompt = turn.optString("prompt"),
                    response = turn.optString("response"),
                    sequence = turn.optLong("seq"),
                )
            }
            return result
        }

        /**
         * Sent immediately after a cancel; see [stop].
         *
         * Deliberately short and final. A longer explanation invites the agent
         * to investigate, and investigating *is* continuing.
         */
        const val STOP_INSTRUCTION =
            "Stop. Do not continue the previous task and do not start a new one. " +
                "Kill any background jobs you started. " +
                "Reply with one short line confirming you have stopped."

        /**
         * Builds the message list a chat window shows from projected turns.
         *
         * Both sides are surfaced even when truncated, because a clipped answer
         * is still an answer; hiding it behind a "…" with no text at all would
         * misrepresent a finished turn as an empty one.
         */
        fun toMessages(turns: List<Turn>): List<ChatMessage> = buildList {
            for (turn in turns) {
                if (turn.prompt.isNotBlank()) add(ChatMessage(fromUser = true, text = turn.prompt))
                if (turn.isAnswered) add(ChatMessage(fromUser = false, text = turn.response))
            }
        }
    }

    /** One exchange as the harness projects it. */
    data class Turn(
        val prompt: String,
        val response: String,
        val sequence: Long,
    ) {
        val isAnswered: Boolean get() = response.isNotBlank()
    }
}

/** One line of the conversation, as the UI shows it. */
data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
)
