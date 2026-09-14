package com.tomppi.enderslicer.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user pastes whatever the harness printed, so parsing has to survive the
 * shapes that actually come out of it - a launch token, extra query parameters,
 * a fragment, or a bare address typed by hand.
 *
 * The token itself is not kept anywhere: the client authenticates from the
 * harness's own `auth.json`, so all parsing has to do with it is strip it off
 * the address.
 */
class HarnessConfigTest {

    @Test
    fun parsesTheLaunchUrlTheHarnessPrints() {
        val config = HarnessConfig.parseLaunchUrl("http://100.64.0.10:3080/?token=abc123")

        assertEquals("http://100.64.0.10:3080", config.baseUrl)
        assertTrue(config.isConfigured)
    }

    @Test
    fun acceptsABareAddress() {
        val config = HarnessConfig.parseLaunchUrl("http://100.64.0.10:3080")

        assertEquals("http://100.64.0.10:3080", config.baseUrl)
        assertTrue(config.isConfigured)
    }

    @Test
    fun trimsTrailingSlashSoPathsDoNotDouble() {
        val config = HarnessConfig.parseLaunchUrl("http://host:3080/")

        assertEquals("http://host:3080", config.baseUrl)
    }

    @Test
    fun dropsTheWholeQueryNotJustTheToken() {
        // The printed URL can carry more than the token, and none of it is part
        // of the address: keeping "?token=abc&session=s1" would send every later
        // request to a path the harness does not serve.
        val config = HarnessConfig.parseLaunchUrl("http://host:3080/?token=abc&session=s1")

        assertEquals("http://host:3080", config.baseUrl)
    }

    @Test
    fun dropsTheFragment() {
        val config = HarnessConfig.parseLaunchUrl("http://host:3080/?token=abc#/chat")

        assertEquals("http://host:3080", config.baseUrl)
    }

    @Test
    fun handlesSurroundingWhitespaceFromAPaste() {
        val config = HarnessConfig.parseLaunchUrl("  http://host:3080/?token=abc  ")

        assertEquals("http://host:3080", config.baseUrl)
    }

    @Test
    fun emptyInputIsNotConfigured() {
        assertFalse(HarnessConfig.parseLaunchUrl("").isConfigured)
        assertFalse(HarnessConfig.parseLaunchUrl("   ").isConfigured)
    }

    @Test
    fun mergingATypedAddressReplacesTheStoredOne() {
        val stored = HarnessConfig(baseUrl = "http://old:3080", workspace = "C:\\work", sessionId = "session-1")

        val merged = stored.mergedWith(
            parsed = HarnessConfig.parseLaunchUrl("http://host:3080/?token=abc"),
            workspace = "C:\\work",
            sessionId = "session-1",
        )

        assertEquals("http://host:3080", merged.baseUrl)
    }

    @Test
    fun mergingAlwaysTakesTheWorkspaceAndSessionFromTheCaller() {
        val stored = HarnessConfig(
            baseUrl = "http://host:3080",
            workspace = "C:\\old",
            sessionId = "session-old",
        )

        val merged = stored.mergedWith(
            parsed = HarnessConfig(baseUrl = "http://host:3080"),
            workspace = "C:\\new",
            sessionId = "session-new",
        )

        assertEquals("C:\\new", merged.workspace)
        assertEquals("session-new", merged.sessionId)
    }

    @Test
    fun mergingABlankAddressKeepsTheStoredOne() {
        val stored = HarnessConfig(baseUrl = "http://host:3080", workspace = "C:\\work", sessionId = "session-1")

        val merged = stored.mergedWith(
            parsed = HarnessConfig(),
            workspace = "C:\\work",
            sessionId = "session-1",
        )

        assertEquals("http://host:3080", merged.baseUrl)
        assertTrue(merged.isConfigured)
    }
}
