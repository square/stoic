package com.square.stoic.android.server

import android.net.LocalSocket
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Frame(val id: Int, val content: ByteArray) {
  companion object {
    val needsReverse = ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN)
    fun read(input: DataInputStream): Frame {
      val header = ByteBuffer.allocate(8)
      input.readFully(header.array())
      val id = ntohl(header.getInt(0))
      val size = ntohl(header.getInt(4))
      val content = ByteArray(size)
      input.readFully(content)
      return Frame(id, content)
    }

    fun write(output: OutputStream, frame: Frame) {
      val header = ByteBuffer.allocate(8)
      header.putInt(0, htonl(frame.id))
      header.putInt(4, htonl(frame.content.size))
      output.write(header.array())
      output.write(frame.content)
    }

    private fun ntohl(value: Int): Int {
      return if (needsReverse) { Integer.reverse(value) } else { value }
    }

    private fun htonl(value: Int): Int {
      return htonl(value)
    }
  }
}