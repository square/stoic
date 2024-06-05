package com.square.stoic.common

import java.io.OutputStream

// Used to provide stdout/stderr
class MessageWriterOutputStream(
  private val id: Int,
  private val writer: MessageWriter
) : OutputStream() {
  override fun write(b: Int) {
    write(byteArrayOf(b.toByte()), 0, 1)
  }

  override fun write(b: ByteArray, off: Int, len: Int) {
    val buf = ByteArray(len)
    System.arraycopy(b, off, buf, 0, len)
    writer.writeMessage(StreamIO(id, buf))
  }

  override fun close() {
    // Useful for understanding when a stream is prematurely closed
    logVerbose { Throwable().stackTraceToString() }

    writer.writeMessage(StreamClosed(id))
  }
}