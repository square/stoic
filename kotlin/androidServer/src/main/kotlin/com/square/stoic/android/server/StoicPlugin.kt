package com.square.stoic.android.server

import android.net.LocalSocket
import android.util.Log
import com.square.stoic.ExitCodeException
import com.square.stoic.Stoic
import com.square.stoic.common.LogLevel
import com.square.stoic.common.MessageReader
import com.square.stoic.common.MessageWriter
import com.square.stoic.common.MessageWriterOutputStream
import com.square.stoic.common.PluginFinished
import com.square.stoic.common.STDERR
import com.square.stoic.common.STDIN
import com.square.stoic.common.STDOUT
import com.square.stoic.common.STOIC_VERSION
import com.square.stoic.common.ServerConnectResponse
import com.square.stoic.common.StartPlugin
import com.square.stoic.common.StreamIO
import com.square.stoic.common.logVerbose
import com.square.stoic.common.minLogLevel
import dalvik.system.DexClassLoader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.Exception
import kotlin.concurrent.thread

class StoicPlugin(private val stoicDir: String, private val socket: LocalSocket) {
  private val writer = MessageWriter(DataOutputStream(socket.outputStream))
  private val reader = MessageReader(DataInputStream(socket.inputStream))

  init {
    logVerbose { "constructed writer from ${writer.dataOutputStream} (underlying ${socket.outputStream})" }
    logVerbose { "constructed reader from ${reader.dataInputStream} (underlying ${socket.inputStream})" }
  }

  fun startThread(pluginId: Int) {
    thread (name = "stoic-plugin") {
      val oldMinLogLevel = minLogLevel
      try {
        Log.d("stoic", "StoicPlugin.startThread")
        writer.writeMessage(ServerConnectResponse(STOIC_VERSION, pluginId))
        Log.d("stoic", "wrote ServerConnectResponse")

        // First message is always start plugin
        val startPlugin = reader.readNext() as StartPlugin
        minLogLevel = LogLevel.valueOf(startPlugin.minLogLevel)

        Log.d("stoic", "startPlugin: $startPlugin (logLevel is $minLogLevel)")

        // The pluginJar is relative to the stoicDir
        val pluginJar = "$stoicDir/${startPlugin.pluginJar}"
        val dexoutDir = File(File(pluginJar).parent, "dexout")
        dexoutDir.mkdir()
        Log.d("stoic", "pluginJar: $pluginJar - exists? ${File(pluginJar).exists()}")
        Log.d("stoic", "dexoutDir: $dexoutDir")
        val parentClassLoader = StoicPlugin::class.java.classLoader

        // It's important to use canonical paths to avoid triggering
        // https://github.com/square/stoic/issues/2
        val classLoader = DexClassLoader(
          File(pluginJar).canonicalPath,
          dexoutDir.canonicalPath,
          null,
          parentClassLoader)
        val stdinOutPipe = PipedOutputStream()
        val stdin = PipedInputStream(stdinOutPipe)
        // Now start pumping messages and input
        val isFinished = AtomicBoolean(false)
        startMessagePumpThread(stdinOutPipe, isFinished)

        val stdout = PrintStream(MessageWriterOutputStream(STDOUT, writer))
        val stderr = PrintStream(MessageWriterOutputStream(STDERR, writer))

        val pluginStoic = Stoic(startPlugin.env, stdin, stdout, stderr)
        Log.d("stoic", "pluginArgs: ${startPlugin.pluginArgs}")
        val args = startPlugin.pluginArgs.toTypedArray()
        val oldClassLoader = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = classLoader
        val exitCode = try {
          Log.d("stoic", "made classLoader: $classLoader (parent: $parentClassLoader)")

          // TODO: It'd be nice to allow people to follow the Java convention of declaring a main
          // method in the manifest, and then insert a StoicMainKt wrapper in the dex.jar (or even
          // insert the manifest into the dex.jar)
          val pluginMainClass = classLoader.loadClass("MainKt")
          Log.d("stoic", "loaded class: $pluginMainClass via ${pluginMainClass.classLoader}")
          val pluginMain = pluginMainClass.getDeclaredMethod("main", args.javaClass)
          Log.d("stoic", "invoking pluginMain: $pluginMain")

          // TODO: error message when passing wrong type isn't very good - should we catch
          // Throwable?
          pluginStoic.callWith(forwardUncaught = false, printErrors = false) {
            pluginMain.invoke(null, args)
          }
          0
        } catch (e: InvocationTargetException) {
          Log.e("stoic", "plugin crashed", e)
          val targetException = e.targetException
          if (targetException !is ExitCodeException) {
            targetException.printStackTrace(stderr)
            1
          } else {
            targetException.code
          }
        } catch (e: ReflectiveOperationException) {
          Log.e("stoic", "problem starting plugin", e)
          e.printStackTrace(stderr)
          1
        } finally {
          // Restore previous classloader
          Thread.currentThread().contextClassLoader = oldClassLoader
        }

        // TODO: Good error message if the plugin closes stdin/stdout/stderr prematurely
        Log.d("stoic", "plugin finished")
        writer.writeMessage(PluginFinished(exitCode))
        isFinished.set(true)
      } catch (e: Throwable) {
        Log.e("stoic", "unexpected", e)

        // We only close the socket in the event of an exception. Otherwise we want to give
        // the buffering thread(s) a chance to complete their transfers
        socket.close()

        // Bring down the process for non-Exception Throwables
        if (e !is Exception) {
          throw e
        }
      } finally {
        Log.d("stoic", "Restoring log level to $oldMinLogLevel")

        // TODO: Make minLogLevel a thread-local - otherwise this is racy
        minLogLevel = oldMinLogLevel
      }
    }
  }

  private fun startMessagePumpThread(stdinOutPipe: PipedOutputStream, isFinished: AtomicBoolean) {
    thread (name = "stoic-plugin-pump") {
      try {
        while (true) {
          val msg = reader.readNext()
          if (msg is StreamIO) {
            assert(msg.id == STDIN)
            // TODO: this will block if the plugin isn't consuming input rapidly enough
            stdinOutPipe.write(msg.buffer)
          }
        }
      } catch (e: IOException) {
        if (isFinished.get()) {
          // This is normal
          Log.d("stoic", "stoic-plugin-pump thread exiting after plugin finished $e")
        } else {
          Log.d("stoic", "stoic plugin input stalled", e)
        }
      } catch (e: Throwable) {
        Log.e("stoic", "stoic-plugin-pump unexpected", e)
      }
    }
  }
}
