package com.tomppi.enderslicer.harness

import org.junit.Assert.assertEquals
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
}
