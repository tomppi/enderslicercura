package com.tomppi.enderslicer.harness

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a conversation out of the `session/list` projection.
 *
 * The shapes here are the ones that actually bite: ids echoed inside other
 * sessions' turn text, a session the harness has forgotten, and a turn that
 * ended without a reply because it was cancelled.
 */
class HarnessChatTest {

    private fun session(
        id: String,
        running: Boolean = false,
        turns: List<Pair<String, String>> = emptyList(),
    ): JSONObject = JSONObject()
        .put("sessionId", id)
        .put("running", running)
        .put(
            "projections",
            JSONObject().put(
                "values",
                JSONObject().put(
                    "turnOutline",
                    JSONArray().apply {
                        turns.forEachIndexed { index, turn ->
                            put(
                                JSONObject()
                                    .put("turn", index + 1)
                                    .put("seq", index + 1)
                                    .put("prompt", turn.first)
                                    .put("response", turn.second),
                            )
                        }
                    },
                ),
            ),
        )

    private fun listOfSessions(vararg items: JSONObject): JSONObject =
        JSONObject().put("items", JSONArray().apply { items.forEach { put(it) } })

    @Test
    fun findsTheSessionTheResponseNamesRatherThanOneItsTextMentions() {
        // Session ids appear inside other sessions' turn text. A search for the
        // string finds the quote; only the item's own field identifies it.
        val list = listOfSessions(
            session("session-chatty", turns = listOf("tell session-wanted to stop" to "done")),
            session("session-wanted", running = true),
        )

        val state = HarnessChat.stateOf(list, "session-wanted")

        assertTrue(state.exists)
        assertTrue(state.running)
        assertEquals(0, state.turns.size)
    }

    @Test
    fun aSessionTheHarnessHasForgottenDoesNotExist() {
        val list = listOfSessions(session("session-other"))

        val state = HarnessChat.stateOf(list, "session-gone")

        assertFalse(state.exists)
        assertFalse(state.running)
        assertTrue(state.turns.isEmpty())
    }

    @Test
    fun anEmptyListIsAMissingSessionNotAnEmptyConversation() {
        // The distinction is the whole point: blanking the chat on a failed
        // lookup erases a conversation that was working.
        assertFalse(HarnessChat.stateOf(JSONObject(), "session-any").exists)
    }

    @Test
    fun aCancelledTurnIsNotAnsweredAndNotRunning() {
        // What a cancel leaves behind: a prompt with a blank response, and the
        // session no longer running. Reading this as "still in flight" is what
        // kept the composer busy until its own timeout.
        val list = listOfSessions(
            session("session-app", turns = listOf("build the thing" to "")),
        )

        val state = HarnessChat.stateOf(list, "session-app")

        assertTrue(state.exists)
        assertFalse(state.running)
        assertFalse(state.answered)
        assertEquals(1, state.turns.size)
    }

    @Test
    fun aQueuedTurnIsRunningBeforeItIsAnswered() {
        val list = listOfSessions(
            session("session-app", running = true, turns = listOf("build the thing" to "")),
        )

        val state = HarnessChat.stateOf(list, "session-app")

        assertTrue(state.running)
        assertFalse(state.answered)
    }

    @Test
    fun aFinishedTurnIsAnswered() {
        val list = listOfSessions(
            session("session-app", turns = listOf("build the thing" to "here is the model")),
        )

        val state = HarnessChat.stateOf(list, "session-app")

        assertTrue(state.answered)
        assertFalse(state.running)
    }

    @Test
    fun turnOrderIsOldestFirstSoTheNewestDecides() {
        val list = listOfSessions(
            session(
                "session-app",
                turns = listOf("first" to "answered", "second" to ""),
            ),
        )

        val state = HarnessChat.stateOf(list, "session-app")

        assertEquals(2, state.turns.size)
        assertEquals("first", state.turns[0].prompt)
        assertEquals("second", state.turns[1].prompt)
        assertFalse(state.answered)
    }

    @Test
    fun messagesSkipAnUnansweredPromptAndKeepBothSidesOfAnAnsweredOne() {
        val turns = listOf(
            HarnessChat.Turn(prompt = "asked", response = "answered", sequence = 1),
            HarnessChat.Turn(prompt = "still waiting", response = "", sequence = 2),
        )

        val messages = HarnessChat.toMessages(turns)

        // An answered turn contributes both sides; the unanswered one
        // contributes its prompt and no empty reply.
        assertEquals(3, messages.size)
        assertEquals("asked", messages[0].text)
        assertTrue(messages[0].fromUser)
        assertEquals("answered", messages[1].text)
        assertFalse(messages[1].fromUser)
        assertEquals("still waiting", messages[2].text)
        assertTrue(messages[2].fromUser)
    }

    private fun page(vararg events: JSONObject): JSONObject =
        JSONObject().put(
            "records",
            JSONArray().apply {
                events.forEach { put(JSONObject().put("type", "event").put("event", it)) }
            },
        )

    private fun assistant(turn: Int, vararg blocks: Pair<String, String>): JSONObject = JSONObject()
        .put("type", "assistant/message")
        .put(
            "data",
            JSONObject().put("turn", turn).put(
                "message",
                JSONObject().put(
                    "content",
                    JSONArray().apply {
                        blocks.forEach { put(JSONObject().put("type", it.first).put("text", it.second)) }
                    },
                ),
            ),
        )

    @Test
    fun fullResponsesCarryTheSpokenTextAndNotTheReasoning() {
        // An assistant message holds the agent's private working as well as its
        // reply. Concatenating every block would put the thinking in the chat.
        val page = page(assistant(1, "reasoning" to "let me think about it", "text" to "here is the answer"))

        assertEquals(mapOf(1 to "here is the answer"), HarnessChat.fullResponsesOf(page))
    }

    @Test
    fun fullResponsesKeepTheLastTextOfATurn() {
        // A turn narrates as it goes; only its closing line is the answer.
        val page = page(
            assistant(1, "text" to "starting"),
            assistant(1, "text" to "the real answer"),
        )

        assertEquals(mapOf(1 to "the real answer"), HarnessChat.fullResponsesOf(page))
    }

    @Test
    fun fullResponsesKeyOnTurnNumber() {
        val page = page(
            assistant(1, "text" to "first"),
            assistant(2, "text" to "second"),
        )

        assertEquals(mapOf(1 to "first", 2 to "second"), HarnessChat.fullResponsesOf(page))
    }

    @Test
    fun fullResponsesIgnoreEverythingThatIsNotAnAssistantMessage() {
        val injected = JSONObject()
            .put("type", "user/message")
            .put(
                "data",
                JSONObject().put(
                    "message",
                    JSONObject().put(
                        "content",
                        JSONArray().put(JSONObject().put("type", "text").put("text", "not a reply")),
                    ),
                ),
            )
        val page = page(injected, JSONObject().put("type", "step/start"), assistant(1, "text" to "reply"))

        assertEquals(mapOf(1 to "reply"), HarnessChat.fullResponsesOf(page))
    }

    @Test
    fun fullResponsesTreatAMissingRecordArrayAsEmpty() {
        assertTrue(HarnessChat.fullResponsesOf(JSONObject()).isEmpty())
    }

    @Test
    fun fullResponsesJoinSeveralTextBlocksInOrder() {
        val page = page(assistant(1, "text" to "one", "text" to "two"))

        assertEquals("one\n\ntwo", HarnessChat.fullResponsesOf(page)[1])
    }
}
