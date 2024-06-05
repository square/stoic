package com.square.stoic.common

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

class NamedPipeSocket private constructor(
  private val rawInputStream: InputStream,
  val inputStream: InputStream,
  private val rawOutputStream: OutputStream,
  val outputStream: OutputStream,
  val connId: Int,
) {
  constructor(inputStream: InputStream, outputStream: OutputStream, connId: Int) : this(inputStream, inputStream, outputStream, outputStream, connId)
  constructor(fifoDir: String) : this(connectNamedPipeSocket(fifoDir))
  private constructor(triple: Triple<InputStream, OutputStream, Int>) : this(triple.first, triple.second, triple.third)

  fun close() {
    rawInputStream.close()
    rawOutputStream.close()
  }

  fun bufferOutput(): NamedPipeSocket {
    if (rawOutputStream == outputStream) {
      return this // already buffered
    }

    val pipedOutputStream = PipedOutputStream()
    val pipedInputStream = PipedInputStream(pipedOutputStream)

    thread(name = "nps-buffer-out") {
      try {
        pipedInputStream.transferTo(rawOutputStream)
      } catch (e: IOException) {
        close()
      }
    }

    return NamedPipeSocket(
      rawInputStream = rawInputStream,
      inputStream = inputStream,
      rawOutputStream = rawOutputStream,
      outputStream = pipedOutputStream,
      connId
    )
  }

  fun bufferInput(): NamedPipeSocket {
    if (rawInputStream == inputStream) {
      return this // already buffered
    }

    val pipedOutputStream = PipedOutputStream()
    val pipedInputStream = PipedInputStream(pipedOutputStream)

    thread(name = "nps-buffer-in") {
      try {
        rawInputStream.transferTo(pipedOutputStream)
      } catch (e: IOException) {
        close()
      }
    }

    return NamedPipeSocket(
      rawInputStream = inputStream,
      inputStream = pipedInputStream,
      rawOutputStream = rawOutputStream,
      outputStream = outputStream,
      connId
    )
  }
}