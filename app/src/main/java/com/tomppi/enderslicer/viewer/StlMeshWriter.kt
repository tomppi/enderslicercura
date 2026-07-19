package com.tomppi.enderslicer.viewer

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object StlMeshWriter {
    fun writeBinary(mesh: StlMesh, destination: File) {
        require(mesh.triangleCount > 0) { "Cannot write an empty STL mesh" }
        require(mesh.interleavedVertices.size == mesh.triangleCount * 18) {
            "STL mesh vertex data does not match its triangle count"
        }
        destination.parentFile?.mkdirs()
        destination.outputStream().buffered().use { output ->
            output.write(ByteArray(80))
            output.write(intLittleEndian(mesh.triangleCount))
            var index = 0
            repeat(mesh.triangleCount) {
                val normalX = mesh.interleavedVertices[index + 3]
                val normalY = mesh.interleavedVertices[index + 4]
                val normalZ = mesh.interleavedVertices[index + 5]
                output.write(floatLittleEndian(normalX))
                output.write(floatLittleEndian(normalY))
                output.write(floatLittleEndian(normalZ))
                repeat(3) { vertex ->
                    val base = index + vertex * 6
                    output.write(floatLittleEndian(mesh.interleavedVertices[base]))
                    output.write(floatLittleEndian(mesh.interleavedVertices[base + 1]))
                    output.write(floatLittleEndian(mesh.interleavedVertices[base + 2]))
                }
                output.write(byteArrayOf(0, 0))
                index += 18
            }
        }
        check(destination.isFile && destination.length() == 84L + mesh.triangleCount * 50L) {
            "Unable to write the transformed STL"
        }
    }

    private fun intLittleEndian(value: Int): ByteArray = ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(value)
        .array()

    private fun floatLittleEndian(value: Float): ByteArray = ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putFloat(value)
        .array()
}
