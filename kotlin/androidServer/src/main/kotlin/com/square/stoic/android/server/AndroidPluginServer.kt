package com.square.stoic.android.server

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.square.stoic.StoicJvmti
import com.square.stoic.common.LogLevel
import com.square.stoic.common.minLogLevel
import com.square.stoic.common.serverSocketName
import com.square.stoic.common.waitSocketName
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.concurrent.thread

@Suppress("unused")
fun main(stoicDir: String) {
  Log.i("stoic", "start of ServerKt.main")

  // RegisterNatives happened before we were called, so we mark Jvmti as ready to use
  StoicJvmti.markInitialized()

  minLogLevel = LogLevel.DEBUG

  //var pidFile: String? = null
  try {
    Log.d("stoic", "stoicDir: $stoicDir")
  //val options = Json.decodeFromString(
  //  JvmtiAttachOptions.serializer(),
  //  File(optionsJsonFromStoicDir(stoicDir)).readText(UTF_8)
  //)
  //Log.d("stoic", "options: $options")
  //if (options.stoicVersion != STOIC_VERSION) {
  //  throw Exception("Mismatched versions: ${options.stoicVersion} and $STOIC_VERSION")
  //}

    // We write a pid file to let the client know that this specific process is now hosting a Stoic
    // server.
  //val pid = android.os.Process.myPid()
  //Log.d("stoic", "writing pid: $pid")
  //pidFile = "${stoicDir}/pid"
  //File(pidFile).writeText(pid.toString(), UTF_8)
  //Log.d("stoic", "pid written")

    // TODO: fix hack - get the pkg from something other than the dir
    val pkg = File(stoicDir).parentFile!!.name

    var nextPluginId = 1
    val server = LocalServerSocket(serverSocketName(pkg))
    val name = server.localSocketAddress.name
    val namespace = server.localSocketAddress.namespace
    Log.d("stoic", "localSocketAddress: ($name, $namespace)")

    thread(name = "stoic-server") {
      Log.d("stoic", "Letting the client that we're up by connecting to the wait socket")
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
      StoicPlugin(stoicDir, socket).startThread(nextPluginId++)
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