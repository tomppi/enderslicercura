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
    fun rejectsCredentialsAndQueryDataInServerUrl() {
        assertTrue(
            runCatching { OctoPrintClient.normalizeBaseUrl("http://user:password@octopi.local") }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { OctoPrintClient.normalizeBaseUrl("http://octopi.local/?token=secret") }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { OctoPrintClient.normalizeBaseUrl("http://octopi.local/#fragment") }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun validatesRemotePathsWithoutDotSegmentTraversal() {
        assertEquals(
            "models/cube.gcode",
            OctoPrintClient.normalizeRemotePath("/models/cube.gcode/", allowBlank = false),
        )
        assertEquals("", OctoPrintClient.normalizeRemotePath("", allowBlank = true))
        assertTrue(
            runCatching { OctoPrintClient.normalizeRemotePath("models/../api", false) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { OctoPrintClient.normalizeRemotePath("models\\cube.gcode", false) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { OctoPrintClient.normalizeRemotePath("models//cube.gcode", false) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun transitionalPrinterStateCountsAsActiveJob() {
        val pausing = OctoPrintUiState(
            printer = OctoPrintPrinterState(operational = true, pausing = true),
        )
        assertTrue(pausing.isTransitioning)
        assertTrue(pausing.hasActiveJob)

        val cancelling = OctoPrintUiState(
            job = OctoPrintJobState(state = "Cancelling"),
        )
        assertTrue(cancelling.isTransitioning)
        assertTrue(cancelling.hasActiveJob)
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
                  "free": "3.2GB",
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

        assertEquals(3_200_000_000L, freeBytes)
        assertEquals(2, files.size)
        assertTrue(files[0].isFolder)
        assertFalse(files[1].isFolder)
        assertEquals(1, files[1].depth)
        assertEquals("calibration/pa.gcode", files[1].path)
    }

    @Test
    fun unsafeServerFilePathsAreSkipped() {
        val (files, _) = OctoPrintJson.parseFiles(
            JSONObject(
                """
                {
                  "files": [
                    {"name":"safe.gcode","path":"safe.gcode","type":"machinecode"},
                    {"name":"escape.gcode","path":"../api/version","type":"machinecode"},
                    {"name":"backslash.gcode","path":"folder\\\\file.gcode","type":"machinecode"}
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("safe.gcode"), files.map { it.path })
    }

    @Test
    fun parsesNumericAndBinaryFreeSpaceValues() {
        assertEquals(
            999_999L,
            OctoPrintJson.parseFiles(JSONObject("{\"free\":999999,\"files\":[]}" )).second,
        )
        assertEquals(
            1_610_612_736L,
            OctoPrintJson.parseFiles(JSONObject("{\"free\":\"1.5GiB\",\"files\":[]}" )).second,
        )
        assertNull(
            OctoPrintJson.parseFiles(JSONObject("{\"free\":\"unknown\",\"files\":[]}" )).second,
        )
    }
}
