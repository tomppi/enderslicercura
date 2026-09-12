package com.tomppi.enderslicer.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user pastes whatever the harness printed, so parsing has to survive the
 * shapes that actually come out of it - a token, extra query parameters, a
 * fragment, or a bare address typed by hand.
 */
class HarnessConfigTest {

    @Test
    fun parsesTheLaunchUrlTheHarnessPrints() {
        val config = HarnessConfig.parseLaunchUrl("http://100.64.0.10:3080/?token=abc123")

        assertEquals("http://100.64.0.10:3080", config.baseUrl)
        assertEquals("abc123", config.token)
        assertTrue(config.isConfigured)
    }

    @Test
    fun acceptsABareAddress() {
        val config = HarnessConfig.parseLaunchUrl("http://100.64.0.10:3080")

        assertEquals("http://100.64.0.10:3080", config.baseUrl)
        assertEquals("", config.token)
        assertTrue(config.isConfigured)
    }

    @Test
    fun trimsTrailingSlashSoPathsDoNotDouble() {
        val config = HarnessConfig.parseLaunchUrl("http://host:3080/")

        assertEquals("http://host:3080", config.baseUrl)
    }

    @Test
    fun stopsTheTokenAtTheNextParameter() {
        val config = HarnessConfig.parseLaunchUrl("http://host:3080/?token=abc&session=s1")

        assertEquals("abc", config.token)
        assertEquals("http://host:3080", config.baseUrl)
    }

    @Test
    fun dropsTheFragment() {
        val config = HarnessConfig.parseLaunchUrl("http://host:3080/?token=abc#/chat")

        assertEquals("abc", config.token)
        assertEquals("http://host:3080", config.baseUrl)
    }

    @Test
    fun handlesSurroundingWhitespaceFromAPaste() {
        val config = HarnessConfig.parseLaunchUrl("  http://host:3080/?token=abc  ")

        assertEquals("http://host:3080", config.baseUrl)
        assertEquals("abc", config.token)
    }

    @Test
    fun emptyInputIsNotConfigured() {
        assertFalse(HarnessConfig.parseLaunchUrl("").isConfigured)
        assertFalse(HarnessConfig.parseLaunchUrl("   ").isConfigured)
    }

    @Test
    fun mergingABareAddressKeepsTheStoredToken() {
        // The address field is a bare URL and parses to an empty token, so
        // saving the parsed value over the stored one silently drops the
        // credential and forces another auth bootstrap on the next connect.
        val stored = HarnessConfig(
            baseUrl = "http://host:3080",
            token = "from-auth-json",
            workspace = "C:\\work",
            sessionId = "session-1",
        )

        val merged = stored.mergedWith(
            parsed = HarnessConfig.parseLaunchUrl("http://host:3080"),
            workspace = "C:\\work",
            sessionId = "session-1",
        )

        assertEquals("from-auth-json", merged.token)
        assertEquals("http://host:3080", merged.baseUrl)
    }

    @Test
    fun mergingAPastedTokenReplacesTheStoredOne() {
        val stored = HarnessConfig(baseUrl = "http://host:3080", token = "stale")

        val merged = stored.mergedWith(
            parsed = HarnessConfig.parseLaunchUrl("http://host:3080/?token=fresh"),
            workspace = "",
            sessionId = "",
        )

        assertEquals("fresh", merged.token)
    }

    @Test
    fun mergingAlwaysTakesTheWorkspaceAndSessionFromTheCaller() {
        val stored = HarnessConfig(
            baseUrl = "http://host:3080",
            token = "kept",
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
        val stored = HarnessConfig(baseUrl = "http://host:3080", token = "kept")

        val merged = stored.mergedWith(
            parsed = HarnessConfig(),
            workspace = "C:\\work",
            sessionId = "session-1",
        )

        assertEquals("http://host:3080", merged.baseUrl)
        assertTrue(merged.isConfigured)
    }
}
