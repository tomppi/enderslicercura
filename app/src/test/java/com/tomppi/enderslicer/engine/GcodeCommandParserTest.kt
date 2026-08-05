package com.tomppi.enderslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GcodeCommandParserTest {
    @Test
    fun parsesLineNumberMixedCaseTabsCompactParametersAndChecksum() {
        val parsed = requireNotNull(GcodeCommand.parse("  n42\tg1x10.5Y-2e1.25 f1200*77 ; ignored"))

        assertEquals("G1", parsed.opcode)
        assertEquals(10.5, parsed.value('X')!!, 0.0)
        assertEquals(-2.0, parsed.value('y')!!, 0.0)
        assertEquals(1.25, parsed.value('E')!!, 0.0)
        assertEquals(1200.0, parsed.value('F')!!, 0.0)
    }

    @Test
    fun preservesOpcodeAliasesForTheCanonicalCommandLayer() {
        val parsed = requireNotNull(GcodeCommand.parse("g01 X1"))
        assertEquals("G01", parsed.opcode)
    }

    @Test
    fun repeatedParameterUsesTheLastFiniteValue() {
        val parsed = requireNotNull(GcodeCommand.parse("G1 X1 X2.5 X-3"))
        assertEquals(-3.0, parsed.value('X')!!, 0.0)
    }

    @Test
    fun compactEIsAnExtrusionParameterNotScientificNotation() {
        val parsed = requireNotNull(GcodeCommand.parse("G1 X1E2"))
        assertEquals(1.0, parsed.value('X')!!, 0.0)
        assertEquals(2.0, parsed.value('E')!!, 0.0)
    }

    @Test
    fun malformedParameterDoesNotHideTheFollowingCompactParameter() {
        val parsed = requireNotNull(GcodeCommand.parse("G1 X Y2 Z.5"))
        assertNull(parsed.value('X'))
        assertEquals(2.0, parsed.value('Y')!!, 0.0)
        assertEquals(0.5, parsed.value('Z')!!, 0.0)
    }

    @Test
    fun commentsAndEmptyLinesDoNotCreateCommands() {
        assertNull(GcodeCommand.parse("   ; comment"))
        assertNull(GcodeCommand.parse("   *42"))
        assertNull(GcodeCommand.parse("N123   ; no command"))
    }

    @Test
    fun hasIsCaseInsensitiveAndMissingValuesRemainAbsent() {
        val parsed = requireNotNull(GcodeCommand.parse("M104 s210"))
        assertTrue(parsed.has('S'))
        assertTrue(parsed.has('s'))
        assertFalse(parsed.has('P'))
        assertEquals(210.0, parsed.value('S')!!, 0.0)
    }
}
