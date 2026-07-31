package com.tomppi.enderslicer.octoprint

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OctoPrintClientTest {
    @Test
    fun normalizesLocalHostAndPreservesPathPrefix() {
        assertEquals(
            "http://octopi.local/",
            OctoPrintClient.normalizeBaseUrl("octopi.local").toString(),
        )
        assertEquals(
            "https://printer.example/octoprint/",
            OctoPrintClient.normalizeBaseUrl("https://printer.example/octoprint").toString(),
        )
    }

    @Test
    fun rejectsCredentialsEmbeddedInServerUrl() {
        val error = runCatching {
            OctoPrintClient.normalizeBaseUrl("http://user:password@octopi.local")
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun parsesPrinterTemperaturesAndFlags() {
        val printer = OctoPrintJson.parsePrinter(
            JSONObject(
                """
                {
                  "temperature": {
                    "tool0": {"actual": 205.4, "target": 210.0, "offset": 0},
                    "bed": {"actual": 59.8, "target": 60.0, "offset": 0}
                  },
                  "state": {
                    "text": "Printing",
                    "flags": {
                      "operational": true,
                      "printing": true,
                      "paused": false,
                      "ready": false,
                      "closedOrError": false
                    }
                  },
                  "sd": {"ready": true}
                }
                """.trimIndent(),
            ),
        )

        assertEquals("Printing", printer.text)
        assertTrue(printer.operational)
        assertTrue(printer.printing)
        assertFalse(printer.paused)
        assertEquals(205.4, printer.tools.getValue("tool0").actual!!, 0.001)
        assertEquals(60.0, printer.bed?.target!!, 0.001)
        assertTrue(printer.sdReady)
    }

    @Test
    fun parsesJobProgressWithoutInventingMissingValues() {
        val job = OctoPrintJson.parseJob(
            JSONObject(
                """
                {
                  "state": "Printing",
                  "job": {
                    "file": {"name": "cube.gcode", "path": "tests/cube.gcode", "origin": "local", "size": 12345},
                    "estimatedPrintTime": 600
                  },
                  "progress": {
                    "completion": 42.5,
                    "filepos": 5000,
                    "printTime": 255,
                    "printTimeLeft": 345
                  }
                }
                """.trimIndent(),
            ),
        )

        assertEquals("cube.gcode", job.fileName)
        assertEquals("tests/cube.gcode", job.filePath)
        assertEquals(42.5, job.completionPercent!!, 0.001)
        assertEquals(255, job.elapsedSeconds)
        assertEquals(345, job.remainingSeconds)
        assertNull(job.currentZ)
    }

    @Test
    fun flattensNestedOctoPrintFolders() {
        val (files, freeBytes) = OctoPrintJson.parseFiles(
            JSONObject(
                """
                {
                  "free": 999999,
                  "files": [
                    {
                      "name": "calibration",
                      "display": "calibration",
                      "path": "calibration",
                      "type": "folder",
                      "origin": "local",
                      "children": [
                        {
                          "name": "pa.gcode",
                          "display": "pa.gcode",
                          "path": "calibration/pa.gcode",
                          "type": "machinecode",
                          "origin": "local",
                          "size": 123
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(999999L, freeBytes)
        assertEquals(2, files.size)
        assertTrue(files[0].isFolder)
        assertFalse(files[1].isFolder)
        assertEquals(1, files[1].depth)
        assertEquals("calibration/pa.gcode", files[1].path)
    }
}
