package com.squareup.stoic.android.server

import android.util.Log
import com.squareup.stoic.ExitCodeException
import com.squareup.stoic.Stoic
import com.squareup.stoic.common.Failed
import com.squareup.stoic.common.FailureCode
import com.squareup.stoic.common.LoadPlugin
import com.squareup.stoic.common.Succeeded
import com.squareup.stoic.common.MessageReader
import com.squareup.stoic.common.MessageWriter
import com.squareup.stoic.common.MessageWriterOutputStream
import com.squareup.stoic.common.PluginFinished
import com.squareup.stoic.common.ProtocolError
import com.squareup.stoic.common.STDERR
import com.squareup.stoic.common.StartPlugin
import com.squareup.stoic.common.STDIN
import com.squareup.stoic.common.STDOUT
import com.squareup.stoic.common.STOIC_PROTOCOL_VERSION
import com.squareup.stoic.common.StreamClosed
import com.squareup.stoic.common.StreamIO
import com.squareup.stoic.common.VerifyProtocolVersion
import com.squareup.stoic.common.logVerbose
import com.squareup.stoic.common.runCommand
import com.squareup.stoic.threadlocals.stoic
import dalvik.system.DexClassLoader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import kotlin.Exception
import kotlin.concurrent.thread

class StoicPlugin(
  private val stoicDir: String,
  extraPlugins: Map<String, StoicNamedPlugin>,
  private val socketInputStream: InputStream,
  private val socketOutputStream: OutputStream,
) {
  private val writer = MessageWriter(DataOutputStream(socketOutputStream))
  private val reader = MessageReader(DataInputStream(socketInputStream))
  private var nextMessage: Any? = null
  private val builtinPlugins: Map<String, StoicNamedPlugin>

  init {
    logVerbose { "constructed writer from ${writer.dataOutputStream} (underlying ${socketOutputStream})" }
    logVerbose { "constructed reader from ${reader.dataInputStream} (underlying ${socketInputStream})" }
    val defaultPlugins = mapOf(
      "stoic-status" to object : StoicNamedPlugin {
        override fun run(args: List<String>): Int {
          stoic.stdout.println(
            """
              protocol-version: $STOIC_PROTOCOL_VERSION
              connected-via: TODO
              builtin-plugins: ${builtinPlugins.keys}
            """.trimIndent()
          )

          return 0
        }
      },
      "stoic-list" to object : StoicNamedPlugin {
        override fun run(args: List<String>): Int {
          builtinPlugins.keys.forEach { stoic.stdout.println(it) }

          return 0
        }
      },
      "stoic-noop" to object : StoicNamedPlugin {
        override fun run(args: List<String>): Int {
          // This is used to ensure the server is running
          return 0
        }
      },
    )
    builtinPlugins = defaultPlugins + extraPlugins
  }

  fun peekMessage(): Any {
    if (nextMessage == null) {
      nextMessage = reader.readNext()
    }

    return nextMessage!!
  }

  fun consumeMessage(): Any {
    return peekMessage().also { nextMessage = null }
  }

  fun handleVersion() {
    val message = consumeMessage() as VerifyProtocolVersion
    if (message.protocolVersion != STOIC_PROTOCOL_VERSION) {
      writer.writeMessage(
        Failed(
          FailureCode.UNSPECIFIED.value,
          "Version mismatch - expected $STOIC_PROTOCOL_VERSION, received ${message.protocolVersion}"
        )
      )
      throw FailedOperationException()
    }

    writer.writeMessage(Succeeded("version check succeeded"))
  }

  fun handlePlugin() {
    while (true) {
      when (peekMessage()) {
        is StartPlugin -> {
          // Once handleStartPlugin succeeds we're done.
          if (handleStartPlugin()) {
            return
          } else {
            continue
          }
        }
        is LoadPlugin -> handleLoadPlugin()
        else -> {
          logVerbose { "Exiting handlePlugin - next message is ${peekMessage()}" }
          return
        }
      }
    }
  }

  /**
   * Returns true for success, false for failure
   */
  fun handleStartPlugin(): Boolean {
    val startPlugin = consumeMessage() as StartPlugin
    val oldClassLoader = Thread.currentThread().contextClassLoader
    try {
      val plugin = if (startPlugin.pluginSha != null) {
        val parentClassLoader = StoicPlugin::class.java.classLoader
        val pluginDir = "$stoicDir/plugin-by-sha/${startPlugin.pluginSha}"
        val pluginJar = File("$pluginDir/${startPlugin.pluginName}.dex.jar")
        if (!pluginJar.exists()) {
          writer.writeMessage(
            Failed(
              FailureCode.PLUGIN_MISSING.value,
              "$pluginJar not loaded"
            )
          )
          return false
        }

        val dexoutDir = File("$pluginDir/${startPlugin.pluginName}-dexout")
        dexoutDir.mkdirs()
        Log.d("stoic", "Making classLoader: (pluginJar: $pluginJar, dexoutDir: $dexoutDir)")

        // It's important to use canonical paths to avoid triggering
        // https://github.com/square/stoic/issues/2
        val classLoader = DexClassLoader(
          pluginJar.canonicalPath,
          dexoutDir.canonicalPath,
          null,
          parentClassLoader)

        Log.d("stoic", "made classLoader: $classLoader (parent: $parentClassLoader)")

        // TODO: It'd be nice to allow people to follow the Java convention of declaring a main
        //   method in the manifest, and then insert a StoicMainKt wrapper in the dex.jar (or even
        //   insert the manifest into the dex.jar)
        val pluginMainClass = classLoader.loadClass("MainKt")
        Log.d("stoic", "loaded class: $pluginMainClass via ${pluginMainClass.classLoader}")
        val args = startPlugin.pluginArgs.toTypedArray()
        val pluginMain = pluginMainClass.getDeclaredMethod("main", args.javaClass)

        object: StoicNamedPlugin {
          override fun run(args: List<String>): Int {
            return try {
              pluginMain.invoke(null, args.toTypedArray())
              0
            } catch (e: InvocationTargetException) {
              Log.e("stoic", "plugin crashed", e)
              val targetException = e.targetException
              if (targetException !is ExitCodeException) {
                targetException.printStackTrace(stoic.stderr)
                1
              } else {
                targetException.code
              }
            } catch (e: ReflectiveOperationException) {
              Log.e("stoic", "problem starting plugin", e)
              e.printStackTrace(stoic.stderr)
              1
            }
          }
        }
      } else if (startPlugin.pluginName != null) {
        val p = builtinPlugins[startPlugin.pluginName]
        if (p == null) {
          val msg = "No builtin plugin named: ${startPlugin.pluginName}"
          Log.i("stoic", msg)
          writer.writeMessage(Failed(FailureCode.PLUGIN_MISSING.value, msg))
          return false
        } else {
          p
        }
      } else {
        throw IllegalArgumentException(
          "At least one of pluginName/pluginSha must be specified: $startPlugin"
        )
      }

      // Plugin may write to stdout/stderr
      writer.openStdoutForWriting()
      writer.openStderrForWriting()

      val stdinOutPipe = PipedOutputStream()
      val stdin = PipedInputStream(stdinOutPipe)
      val stdout = PrintStream(MessageWriterOutputStream(STDOUT, writer))
      val stderr = PrintStream(MessageWriterOutputStream(STDERR, writer))
      val pluginStoic = Stoic(startPlugin.env, stdin, stdout, stderr)

      writer.writeMessage(Succeeded("Plugin started"))

      // TODO: need to pump
      var exitCode = -1
      val t = thread {
        exitCode = pluginStoic.callWith {
          plugin.run(startPlugin.pluginArgs)
        }

        // We write PluginFinished to signal the client to send StreamClosed(STDIN), which signals us
        // to stop pumping messages
        writer.writeMessage(PluginFinished(exitCode))
      }

      while (true) {
        val msg = consumeMessage()
        when (msg) {
          is StreamIO -> {
            if (msg.id != STDIN) { throw IllegalArgumentException("Unexpected stream id: ${msg.id}") }
            stdinOutPipe.write(msg.buffer)
          }
          is StreamClosed -> {
            if (msg.id != STDIN) { throw IllegalArgumentException("Unexpected stream id: ${msg.id}") }
            logVerbose { "StreamClosed(STDIN)" }
            break
          }
        }
      }
      t.join()
    } finally {
      Thread.currentThread().contextClassLoader = oldClassLoader
    }

    return true
  }

  fun handleLoadPlugin() {
    val loadPlugin = consumeMessage() as LoadPlugin
    val loadPluginStreamIO = consumeMessage() as StreamIO
    if (loadPlugin.pseudoFd != loadPluginStreamIO.id) {
      throw IllegalStateException(
        "Pseudo fd mismatch: ${loadPlugin.pseudoFd} != ${loadPluginStreamIO.id}"
      )
    }

    // TODO: support streaming the plugin across multiple StreamIO messages
    val streamClosed = consumeMessage() as StreamClosed
    if (loadPlugin.pseudoFd != streamClosed.id) {
      throw IllegalStateException(
        "Pseudo fd mismatch: ${loadPlugin.pseudoFd} != ${streamClosed.id}"
      )
    }

    val pluginByShaDir = File("$stoicDir/plugin-by-sha/${loadPlugin.pluginSha}")
    if (pluginByShaDir.exists()) {
      // We are reloading the directory - need to clear it first
      runCommand(listOf("rm", "-rf", pluginByShaDir.canonicalPath))
    }

    pluginByShaDir.mkdirs()
    val pluginJar = File(pluginByShaDir, "${loadPlugin.pluginName}.dex.jar")
    pluginJar.writeBytes(loadPluginStreamIO.buffer)
    pluginJar.setWritable(false)
    writer.writeMessage(Succeeded("Load plugin succeeded"))
  }

  fun pluginMain() {
    Log.i("stoic", "pluginMain")
    try {
      handleVersion()
      handlePlugin()
    } catch (e: Throwable) {
      Log.e("stoic", "pluginMain threw", e)
      writer.writeMessage(ProtocolError(e.stackTraceToString()))

      // TODO: Instead of this hacky sleep, we should wait for an ACK from the client
      // Give the message time to make it to the other side before we close the connection
      Thread.sleep(1000)
    }
  }
}

class FailedOperationException : Exception()