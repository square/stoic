package com.squareup.stoic.common

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

const val stoicDeviceDir = "/data/local/tmp/stoic"
const val stoicDeviceSyncDir = "$stoicDeviceDir/sync"
const val stoicDeviceSyncPluginDir = "$stoicDeviceSyncDir/plugins"
const val stoicDeviceDevJarDir = "$stoicDeviceDir/dev_jar"
const val shebangJarDir = "$stoicDeviceDir/shebang_jar"
const val stoicDemoAppWithoutSdk = "com.squareup.stoic.demoapp.withoutsdk"
const val runAsCompat = "$stoicDeviceSyncDir/bin/run-as-compat"

class PluginClient(
  val pluginDexJar: String?,
  val pluginParsedArgs: PluginParsedArgs,
  inputStream: InputStream,
  outputStream: OutputStream
) {
  val writer = MessageWriter(DataOutputStream(outputStream))
  val reader = MessageReader(DataInputStream(inputStream))
  var nextMessage: Any? = null

  fun peekMessage(): Any {
    if (nextMessage == null) {
      nextMessage = reader.readNext()
    }

    return nextMessage!!
  }

  fun consumeMessage(): Any {
    return peekMessage().also { nextMessage = null }
  }

  fun handleVersionResult() {
    val succeeded = consumeMessage() as Succeeded
    logDebug { succeeded.message }
  }

  fun handleStartPluginResult() {
    val msg = consumeMessage()
    when (msg) {
      is Succeeded -> return // nothing more to do
      is Failed -> {
        when (msg.failureCode) {
          FailureCode.PLUGIN_MISSING.value -> {} // fall through to load plugin
          else -> throw IllegalStateException(msg.toString())
        }
      }
      else -> throw IllegalStateException("Unexpected message $msg")
    }

    // Need to load plugin
    if (pluginDexJar == null) {
      throw PithyException(
        """
          Plugin not found: ${pluginParsedArgs.pluginModule}
          To list available plugins: stoic tool list
        """.trimIndent()
      )
    }

    val loadPluginPseudoFd = writer.openPseudoFdForWriting()
    val pluginBytes = File(pluginDexJar).readBytes()
    val pluginSha = computeSha256(pluginBytes)
    writer.writeMessage(
        LoadPlugin(
        pluginName = pluginParsedArgs.pluginModule,
        pluginSha = pluginSha,
        pseudoFd = loadPluginPseudoFd,
      )
    )
    writer.writeMessage(
      StreamIO(
        id = loadPluginPseudoFd,
        buffer = pluginBytes
      )
    )
    writer.closePseudoFdForWriting(loadPluginPseudoFd)
    writer.writeMessage(
      StartPlugin(
        pluginName = pluginParsedArgs.pluginModule,
        pluginSha = pluginSha,
        pluginArgs = pluginParsedArgs.pluginArgs,
        minLogLevel = minLogLevel.name,
        env = pluginParsedArgs.pluginEnvVars
      )
    )
    val loadPluginResult = consumeMessage() as Succeeded
    logVerbose { loadPluginResult.toString() }
    val startPluginResult = consumeMessage() as Succeeded
    logVerbose { startPluginResult.toString() }
  }

  fun handle(): Int {
    logDebug { "reader/writer constructed" }

    // Since we're a client, we will write to stdin (and read from stdout/stderr)
    writer.openStdinForWriting()
    writer.writeMessage(VerifyProtocolVersion(STOIC_PROTOCOL_VERSION))
    writer.writeMessage(
      StartPlugin(
        pluginName = pluginParsedArgs.pluginModule,
        pluginSha = pluginDexJar?.let { computeSha256(File(it).readBytes()) },
        pluginArgs = pluginParsedArgs.pluginArgs,
        minLogLevel = minLogLevel.name,
        env = pluginParsedArgs.pluginEnvVars,
      )
    )

    // To minimize roundtrips, we don't read the version result until after we've written RunPlugin
    handleVersionResult()
    handleStartPluginResult()
    while (true) {
      val msg = consumeMessage()
      when (msg) {
        is StreamIO -> {
          when (msg.id) {
            STDOUT -> System.out.write(msg.buffer)
            STDERR -> System.err.write(msg.buffer)
            else -> throw IllegalArgumentException("Unrecognized stream id: ${msg.id}")
          }
        }
        is PluginFinished -> {
          // To allow the server to stop pumping stdin cleanly, we write a StreamClosed message.
          writer.writeMessage(StreamClosed(STDIN))
          return msg.exitCode
        }
        else -> throw IllegalArgumentException("Unexpected msg: $msg")
      }
    }
  }
}

fun computeSha256(bytes: ByteArray): String {
  val digest = MessageDigest.getInstance("SHA-256")
  val hashBytes = digest.digest(bytes)
  return hashBytes.joinToString("") { "%02x".format(it) }
}

