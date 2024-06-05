package com.square.stoic.common

import java.io.DataInputStream
import java.io.DataOutputStream

class MultiplexedReader(private val stream: DataInputStream) {
    fun read(): Pair<Int, ByteArray> {
        System.err.println("reading id (from thread: ${Thread.currentThread().id})")
        val id = stream.readInt()
        System.err.println("read id: $id")
        val payloadSize = stream.readInt()
        System.err.println("read payloadSize: $payloadSize")

        val payload = ByteArray(payloadSize)
        stream.readFully(payload)
        System.err.println("read payload")

        return Pair(id, payload)
    }
}

class MultiplexedWriter(private val stream: DataOutputStream) {
    fun write(id: Int, buffer: ByteArray) {
        write(id, buffer, 0, buffer.size)
    }

    fun write(id: Int, buffer: ByteArray, offset: Int, length: Int) {
        System.err.println("writing id: $id (from thread ${Thread.currentThread().id})")
        stream.writeInt(id)
        System.err.println("writing length: $length")
        stream.writeInt(length)
        System.err.println("writing payload")
        stream.write(buffer, offset, length)
        System.err.println("flushing")
        stream.flush()
        System.err.println("flushed")
    }
}
