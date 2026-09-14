package com.tomppi.enderslicer.harness

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of a prompt's content blocks.
 *
 * This is the difference between an image reaching the agent and the agent
 * being handed a message that merely mentions one. Uploading stages the bytes
 * against the session; only a `file` part naming the receipt puts them in the
 * message, and the app used to discard that receipt.
 */
class HarnessClientTest {

    @Test
    fun aPromptWithNoAttachmentIsJustTheText() {
        val content = HarnessClient.promptContent("build this", null)

        assertEquals(1, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("build this", content.getJSONObject(0).getString("text"))
    }

    @Test
    fun anAttachmentIsNamedBeforeTheText() {
        val content = HarnessClient.promptContent("build this", "receipt-1")

        assertEquals(2, content.length())
        assertEquals("file", content.getJSONObject(0).getString("type"))
        assertEquals("receipt-1", content.getJSONObject(0).getString("receiptId"))
        assertEquals("text", content.getJSONObject(1).getString("type"))
        assertEquals("build this", content.getJSONObject(1).getString("text"))
    }

    @Test
    fun aBlankReceiptIsNotAnAttachment() {
        // An upload that yielded nothing usable must not leave an empty receipt
        // in the message: the harness resolves receipts by id and would refuse
        // the whole prompt.
        assertEquals(1, HarnessClient.promptContent("build this", "").length())
        assertEquals(1, HarnessClient.promptContent("build this", "   ").length())
    }

    @Test
    fun aResponseBodyPastTheLimitIsRefusedInsteadOfBuffered() {
        // A wrong host, a captive portal or a hostile server can answer forever:
        // the read timeout bounds idle time only, so the body needs its own cap.
        val error = runCatching {
            HarnessClient("http://127.0.0.1:1")
                .readAll(ByteArrayInputStream(ByteArray(17 * 1024 * 1024)))
        }.exceptionOrNull()

        assertTrue(error is HarnessException)
        assertEquals("http-response-too-large", (error as HarnessException).code)
        assertTrue(error.message.orEmpty().contains("16 MB limit"))
    }

    @Test
    fun aBodyWithinTheLimitIsReadWholeAndDecoded() {
        val text = "{\"ok\":true,\"note\":\"caf\u00e9\"}"
        val read = HarnessClient("http://127.0.0.1:1").readAll(text.toByteArray(Charsets.UTF_8).inputStream())

        assertEquals(text, read)
    }
}
