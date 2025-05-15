package com.square.stoic.android.server

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.square.stoic.StoicJvmti
import com.square.stoic.common.JvmtiAttachOptions
import com.square.stoic.common.LogLevel
import com.square.stoic.common.STOIC_PROTOCOL_VERSION
import com.square.stoic.common.minLogLevel
import com.square.stoic.common.optionsJsonFromStoicDir
import com.square.stoic.common.serverSocketName
import com.square.stoic.common.waitSocketName
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

val serverRunning = AtomicBoolean(false)
fun ensureServer(stoicDir: String) {
  if (serverRunning.compareAndSet(false, true)) {
    startServer(stoicDir)
  }
}

private fun startServer(stoicDir: String) {
  try {
    Log.d("stoic", "stoicDir: $stoicDir")
    val options = Json.decodeFromString(
      JvmtiAttachOptions.serializer(),
      File(optionsJsonFromStoicDir(stoicDir)).readText(UTF_8)
    )
    Log.d("stoic", "options: $options")
    if (options.stoicVersion != STOIC_PROTOCOL_VERSION) {
      throw Exception("Mismatched versions: ${options.stoicVersion} and $STOIC_PROTOCOL_VERSION")
    }

    // TODO: fix hack - get the pkg from something other than the dir
    val pkg = File(stoicDir).parentFile!!.name

    var nextPluginId = 1
    val server = LocalServerSocket(serverSocketName(pkg))
    val name = server.localSocketAddress.name
    val namespace = server.localSocketAddress.namespace
    Log.d("stoic", "localSocketAddress: ($name, $namespace)")

    thread(name = "stoic-server") {
      Log.d("stoic", "Letting the client know that we're up by connecting to the wait socket")
      try {
        LocalSocket().also {
          it.connect(LocalSocketAddress(waitSocketName(pkg)))
          Log.d("stoic", "connected, writing")
          val writer = it.outputStream.bufferedWriter(UTF_8)
          writer.write("Server up\n")
          Log.d("stoic", "wrote, flushing")
          writer.flush()
          Log.d("stoic", "flushing, closing the socket")
          it.close()
          Log.d("stoic", "Socket closed. Ending the wait thread (this is good!)")
        }
      } catch (e: Throwable) {
        Log.d("stoic", "Failed to connect", e)
        throw e
      }
    }

    while (true) {
      val socket = server.accept()
      thread (name = "stoic-plugin") {
        try {
          StoicPlugin(stoicDir, mapOf(), socket.inputStream, socket.outputStream).pluginMain(nextPluginId++)
        } catch (e: Throwable) {
          Log.e("stoic", "unexpected", e)

          // We only close the socket in the event of an exception. Otherwise we want to give
          // the buffering thread(s) a chance to complete their transfers
          socket.close()

          // Bring down the process for non-Exception Throwables
          if (e !is Exception) {
            throw e
          }
        }
      }
    }
  } catch (e: Throwable) {
    Log.e("stoic", "unexpected", e)
    throw e;

  //if (pidFile != null) {
  //  Log.e("stoic", "deleting pid file: $pidFile")
  //  File(pidFile).also {
  //    if (it.exists()) {
  //      it.delete()
  //    }
  //  }
  //  Log.e("stoic", "pid file deleted")
  //}
  }
}