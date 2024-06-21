package com.square.stoic.common

import com.square.stoic.common.MessageType.PLUGIN_FINISHED
import com.square.stoic.common.MessageType.SERVER_CONNECT_RESPONSE
import com.square.stoic.common.MessageType.START_PLUGIN
import com.square.stoic.common.MessageType.STREAM_CLOSED
import com.square.stoic.common.MessageType.STREAM_IO

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

const val STOIC_VERSION = 1
const val STDIN = 0
const val STDOUT = 1
const val STDERR = 2

// json-encoded and base64-encoded and sent as the JVMTI attach options
@Serializable
data class JvmtiAttachOptions(
  val stoicVersion: Int,
)

// Sent back to the client when it first establishes connection
@Serializable
data class ServerConnectResponse(
  val stoicVersion: Int,
  val connId: Int,
)

enum class MessageType(val code: Int) {
  START_PLUGIN(1),
  STREAM_CLOSED(2),
  STREAM_IO(3),
  SERVER_CONNECT_RESPONSE(4),
  PLUGIN_FINISHED(5),
}

// Sent over the control plane (host -> android) to instruct the android server
// to start a new plugin
@Serializable
data class StartPlugin(
  val pluginJar: String,
  val pluginArgs: List<String>,
  val minLogLevel: String,
  val env: Map<String, String>,
)

// Sent over the stdin/stdout/stderr streams - not line-buffered
// Not JSON-serializable - we don't want to JSON-encode bytes
class StreamIO(val id: Int, val buffer: ByteArray)

// Sent over the control plane (both directions) to inform about a stream
// closing (either an input stream reaching EOF or an output stream breaking)
@Serializable
data class StreamClosed(val id: Int)

@Serializable
data class PluginFinished(val exitCode: Int)


class MessageWriter(val dataOutputStream : DataOutputStream) {
  @Synchronized
  fun writeMessage(msg: Any) {
    logVerbose { "writing: $msg" }
    val msgType = when (msg) {
      is StreamIO -> STREAM_IO
      is StartPlugin -> START_PLUGIN
      is StreamClosed -> STREAM_CLOSED
      is ServerConnectResponse -> SERVER_CONNECT_RESPONSE
      is PluginFinished -> PLUGIN_FINISHED
      else -> throw IllegalArgumentException()
    }

    dataOutputStream.writeInt(msgType.code)

    if (msg is StreamIO) {
      dataOutputStream.writeInt(msg.id)
      dataOutputStream.writeInt(msg.buffer.size)
      dataOutputStream.write(msg.buffer)
    } else {
      val json = when (msg) {
        is StartPlugin -> Json.encodeToString(msg)
        is StreamClosed -> Json.encodeToString(msg)
        is ServerConnectResponse -> Json.encodeToString(msg)
        is PluginFinished -> Json.encodeToString(msg)
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
    val msg: Any = if (msgType == STREAM_IO.code) {
      val id = dataInputStream.readInt()
      val size = dataInputStream.readInt()
      StreamIO(id, ByteArray(size).also { dataInputStream.readFully(it) })
    } else {
      val size = dataInputStream.readInt()
      val json = ByteArray(size).also { dataInputStream.readFully(it) }.toString(StandardCharsets.UTF_8)
      when (msgType) {
        START_PLUGIN.code -> Json.decodeFromString<StartPlugin>(json)
        STREAM_CLOSED.code -> Json.decodeFromString<StreamClosed>(json)
        SERVER_CONNECT_RESPONSE.code -> Json.decodeFromString<ServerConnectResponse>(json)
        PLUGIN_FINISHED.code -> Json.decodeFromString<PluginFinished>(json)
        else -> throw IllegalStateException("msgType: $msgType")
      }
    }

    logVerbose { "read $msg" }

    return msg
  }
}
