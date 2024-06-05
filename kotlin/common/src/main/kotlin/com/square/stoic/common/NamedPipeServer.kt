package com.square.stoic.common

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread

// This is to guard against corruption
const val NPS_MAGIC_PREFIX = 0xfefefefe.toInt()
const val NPS_MAGIC_CENTER = 0xfdfdfdfd.toInt()
const val NPS_MAGIC_SUFFIX = 0xfcfcfcfc.toInt()

fun npsServerFromFifoDir(baseDir: String): String {
  return "$baseDir/server"
}

fun npsConnDir(baseDir: String, connId: Int): String {
  return String.format("$baseDir/conn/%08x", connId)
}
fun npsServerIn(baseDir: String, id: Int): String {
  val connDir = npsConnDir(baseDir, id)
  return "$connDir/in.pipe"
}

fun npsServerOut(baseDir: String, id: Int): String {
  val connDir = npsConnDir(baseDir, id)
  return "$connDir/out.pipe"
}

fun connectNamedPipeSocket(baseDir: String): Triple<InputStream, OutputStream, Int> {
  val idProvider = DataInputStream(FileInputStream(npsServerFromFifoDir(baseDir)))
  val connId = readNamedPipeConnectionId(idProvider)
  val outputStream = FileOutputStream(npsServerOut(baseDir, connId))
  val inputStream = FileInputStream(npsServerIn(baseDir, connId))

  return Triple(inputStream, outputStream, connId)
}

fun readNamedPipeConnectionId(input: InputStream): Int {
  val dataInputStream = DataInputStream(input)
  val prefix = dataInputStream.readInt()
  val connectionId = dataInputStream.readInt()
  val suffix = dataInputStream.readInt()
  assert(prefix == NPS_MAGIC_PREFIX)
  assert(suffix == NPS_MAGIC_SUFFIX)

  return connectionId
}

// Like a SocketServer, only this is based on named-pipes (aka mkfifo)
class NamedPipeServer(private val baseDir: String) {
  // We limit the number of simultaneous connections to a sane number
  private val queue = ArrayBlockingQueue<Int>(5)

  init {
    File(baseDir).also {
      if (!it.exists()) {
        it.mkdirs()
      }
    }

    startOfferIdsThread()
  }

  // Need to offer ids to anyone who wants to connect
  private fun startOfferIdsThread() {
    val serveFifo = File(npsServerFromFifoDir(baseDir))
    var nextId = 0
    thread(name = "NamedPipeServer") {
      while (true) {
        logDebug { "beginning of serve loop for $serveFifo" }
        var output: DataOutputStream? = null
        try {
          // For robustness, I delete and recreate each time. Otherwise there is a chance that some
          // client will have read only part and then the next client that connects will read
          // garbage
          if (serveFifo.exists()) {
            serveFifo.delete()
          }
          ProcessBuilder(listOf("mkfifo", serveFifo.absolutePath)).start().waitFor()

          val connId = nextId++

          // Will block until someone tries to connect
          output = DataOutputStream(FileOutputStream(serveFifo))

          // Make sure the fifos are ready for the connection
          File(npsConnDir(baseDir, connId)).mkdirs()
          val inFifoPath = npsServerIn(baseDir, connId)
          val outFifoPath = npsServerOut(baseDir, connId)
          if (ProcessBuilder(listOf("mkfifo", inFifoPath)).start().waitFor() != 0) {
            throw Exception("Failed to create fifo")
          }
          if (ProcessBuilder(listOf("mkfifo", outFifoPath)).start().waitFor() != 0) {
            throw Exception("Failed to create fifo")
          }

          // Tell our thread to start waiting for clients to connect to these pipes
          queue.offer(connId)

          // Tell the next client about the connId
          output.writeInt(NPS_MAGIC_PREFIX)
          output.writeInt(connId)
          output.writeInt(NPS_MAGIC_SUFFIX)
          output.flush()
        } catch (e: IOException) {
          logDebug { "caught\n${e.stackTraceToString()}" }
        } finally {
          if (output != null) {
            try {
              output.close()
            } catch (e: IOException) {
              logDebug { "caught (while closing)\n${e.stackTraceToString()}" }
            }
          }
        }
      }
    }

  }

  fun accept(): NamedPipeSocket {
    val connId = queue.take()
    val inFifoPath = npsServerIn(baseDir, connId)
    val outFifoPath = npsServerOut(baseDir, connId)
    val inputStream = FileInputStream(inFifoPath)
    val outputStream = FileOutputStream(outFifoPath)
    logDebug { "NamedPipeSocket inputStream constructed from $inFifoPath: $inputStream" }
    logDebug { "NamedPipeSocket outputStream constructed from $outFifoPath: $outputStream" }

  //val lsIn = ProcessBuilder(listOf("ls", "-il", inFifoPath)).start()
  //lsIn.waitFor()
  //logVerbose { lsIn.inputStream.bufferedReader().readText() }

  //val lsOut = ProcessBuilder(listOf("ls", "-il", outFifoPath)).start()
  //lsOut.waitFor()
  //logVerbose { lsOut.inputStream.bufferedReader().readText() }

    return NamedPipeSocket(inputStream, outputStream, connId)
  }
}