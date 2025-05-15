package com.square.stoic.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

const val STOIC_PROTOCOL_VERSION = 2
const val STDIN = 0
const val STDOUT = 1
const val STDERR = 2

// json-encoded and base64-encoded and sent as the JVMTI attach options
@Serializable
data class JvmtiAttachOptions(
  val stoicVersion: Int,
)

enum class MessageType(val code: Int) {
  STREAM_IO(1),
  VERIFY_PROTOCOL_VERSION(2),
  START_PLUGIN(3),
  LOAD_PLUGIN(5),
  PLUGIN_FINISHED(6),
  STREAM_CLOSED(7),
  SUCCEEDED(8),
  FAILED(9),
  PROTOCOL_ERROR(10),
}

enum class FailureCode(val value: Int) {
  UNSPECIFIED(0),
  PLUGIN_MISSING(1),
}

@Serializable
data class VerifyProtocolVersion(
  val protocolVersion: Int
)

@Serializable
data class StartPlugin(
  val pluginName: String?,
  val pluginSha: String?,
  val pluginArgs: List<String>,
  val minLogLevel: String,
  val env: Map<String, String>
)

@Serializable
data class LoadPlugin(
  val pluginName: String?,
  val pluginSha: String,
  val pseudoFd: Int,
)

@Serializable
data class PluginFinished(
  val exitCode: Int,
)

// Sent over the stdin/stdout/stderr streams - not line-buffered
// Not JSON-serializable - we don't want to JSON-encode bytes
class StreamIO(val id: Int, val buffer: ByteArray)

// Sent over the control plane (both directions) to inform about a stream
// closing (either an input stream reaching EOF or an output stream breaking)
@Serializable
data class StreamClosed(val id: Int)

@Serializable
data class Succeeded(val message: String)

@Serializable
data class Failed(val failureCode: Int, val message: String)

@Serializable
data class ProtocolError(val message: String)


class MessageWriter(val dataOutputStream : DataOutputStream) {
  val pseudoFdsOpenForWriting = mutableSetOf<Int>()
  var nextAvailablePseudoFd = 3

  fun openStdinForWriting() {
    pseudoFdsOpenForWriting.add(STDIN)
  }

  fun openStdoutForWriting() {
    pseudoFdsOpenForWriting.add(STDOUT)
  }

  fun openStderrForWriting() {
    pseudoFdsOpenForWriting.add(STDERR)
  }

  fun openPseudoFdForWriting(): Int {
    val pseudoFd = nextAvailablePseudoFd++
    pseudoFdsOpenForWriting.add(pseudoFd)
    return pseudoFd
  }

  fun closePseudoFdForWriting(pseudoFd: Int) {
    if (pseudoFd !in pseudoFdsOpenForWriting) {
      throw IllegalArgumentException("Unrecognized pseudo-fd - already closed?")
    }

    pseudoFdsOpenForWriting.remove(pseudoFd)
    writeMessage(StreamClosed(pseudoFd))
  }

  @Synchronized
  fun writeMessage(msg: Any) {
    logVerbose { "writing: $msg" }
    val msgType = when (msg) {
      is VerifyProtocolVersion -> MessageType.VERIFY_PROTOCOL_VERSION
      is StartPlugin -> MessageType.START_PLUGIN
      is LoadPlugin -> MessageType.LOAD_PLUGIN
      is PluginFinished -> MessageType.PLUGIN_FINISHED
      is StreamIO -> MessageType.STREAM_IO
      is StreamClosed -> MessageType.STREAM_CLOSED
      is Succeeded -> MessageType.SUCCEEDED
      is Failed -> MessageType.FAILED
      is ProtocolError -> MessageType.PROTOCOL_ERROR
      else -> throw IllegalArgumentException()
    }

    dataOutputStream.writeInt(msgType.code)

    if (msg is StreamIO) {
      dataOutputStream.writeInt(msg.id)
      dataOutputStream.writeInt(msg.buffer.size)
      dataOutputStream.write(msg.buffer)
    } else {
      val json = when (msg) {
        is VerifyProtocolVersion -> Json.encodeToString(msg)
        is StartPlugin -> Json.encodeToString(msg)
        is LoadPlugin -> Json.encodeToString(msg)
        is PluginFinished -> Json.encodeToString(msg)
        is StreamClosed -> Json.encodeToString(msg)
        is Succeeded -> Json.encodeToString(msg)
        is Failed -> Json.encodeToString(msg)
        is ProtocolError -> Json.encodeToString(msg)
        else -> throw IllegalArgumentException("Unexpected msg: $msg")
      }
      val bytes = json.toByteArray(StandardCharsets.UTF_8)
      dataOutputStream.writeInt(bytes.size)
      dataOutputStream.write(bytes)
    }

    logVerbose { "wrote $msg" }
    dataOutputStream.flush()
    logVerbose { "flushed $dataOutputStream" }
  }
}

class MessageReader(val dataInputStream: DataInputStream) {
  @Synchronized
  fun readNext(): Any {
    logVerbose { "readNext: attempting to read msgType" }
    val msgType = dataInputStream.readInt()
    logVerbose { "readNext: read $msgType" }
    val msg: Any = if (msgType == MessageType.STREAM_IO.code) {
      val id = dataInputStream.readInt()
      val size = dataInputStream.readInt()
      StreamIO(id, ByteArray(size).also { dataInputStream.readFully(it) })
    } else {
      val size = dataInputStream.readInt()
      val json = String(ByteArray(size).also { dataInputStream.readFully(it) })
      logVerbose { "json: $json" }
      when (msgType) {
        MessageType.VERIFY_PROTOCOL_VERSION.code -> Json.decodeFromString<VerifyProtocolVersion>(json)
        MessageType.START_PLUGIN.code -> Json.decodeFromString<StartPlugin>(json)
        MessageType.LOAD_PLUGIN.code -> Json.decodeFromString<LoadPlugin>(json)
        MessageType.PLUGIN_FINISHED.code -> Json.decodeFromString<PluginFinished>(json)
        MessageType.STREAM_CLOSED.code -> Json.decodeFromString<StreamClosed>(json)
        MessageType.SUCCEEDED.code -> Json.decodeFromString<Succeeded>(json)
        MessageType.FAILED.code -> Json.decodeFromString<Failed>(json)
        MessageType.PROTOCOL_ERROR.code -> Json.decodeFromString<ProtocolError>(json)
        else -> throw IllegalStateException("msgType: $msgType")
      }
    }

    logVerbose { "read $msg" }

    return msg
  }
}
