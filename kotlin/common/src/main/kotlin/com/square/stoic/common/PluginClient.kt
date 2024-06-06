package com.square.stoic.common

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.ProcessBuilder.Redirect
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.net.ConnectException
import kotlin.concurrent.thread

const val stoicDeviceDir = "/data/local/tmp/stoic"
const val stoicDeviceSyncDir = "$stoicDeviceDir/sync"
const val stoicDeviceSyncPluginDir = "$stoicDeviceSyncDir/plugins"
const val stoicDeviceDevJarDir = "$stoicDeviceDir/dev_jar"
const val shebangJarDir = "$stoicDeviceDir/shebang_jar"
const val stoicExamplePkg = "com.square.stoic.example"
const val runAsCompat = "$stoicDeviceSyncDir/bin/run-as-compat"

// Base class for connecting to AndroidServer. Overridden by HostClient and AndroidClient.
abstract class PluginClient(
  val args: PluginParsedArgs
) {
  fun run(): Int {
    if (!args.restartApp) {
      try {
        return fastPath()
      } catch (e: ConnectException) {
        logWarn { "Plugin fast-path failed. Falling back to plugin slow-path." }
        logDebug { e.stackTraceToString() }
      }
    }

    return slowPath()
  }

  private fun fastPath(): Int {
    val pkg = args.pkg
    val pkgSocat = "./stoic/socat"  // We start in home
    val serverAddress = serverSocketName(pkg)
    val process = adbShellPb("$runAsCompat $pkg $pkgSocat - ABSTRACT-CONNECT:$serverAddress")
      .redirectError(Redirect.INHERIT)
      .start()
    return attemptPlugin(process)
  }

  protected fun attemptPlugin(process: Process): Int {
    try {
      val inputStream = process.inputStream
      val outputStream = process.outputStream
      // TODO: monitor errorStream
      // We expect two classes of error messages:
      // 1. If socat wasn't copied over yet:
      //    run-as: exec failed for /data/user/0/com.square.stoic.example/stoic/socat: No such file or directory
      // 2. If the server isn't running
      //    2024/05/28 19:42:59 socat[17319] E connect(, AF=1 "\0/stoic/com.square.stoic.example/server", 42): Connection refused
      // Those are fine - the remedy is to start the server
      // Anything else we should logError for.

      return runPlugin(inputStream, outputStream)
    } catch (e: EOFException) {
      throw ConnectException("socat failed")
    } finally {
      process.destroyForcibly()
    }
  }

  private fun runPlugin(inputStream: InputStream, outputStream: OutputStream): Int {
    val pkg = args.pkg

    val writer = MessageWriter(DataOutputStream(outputStream))
    val reader = MessageReader(DataInputStream(inputStream))
    logDebug { "reader/writer constructed" }

    val connectedLatch = CountDownLatch(1)
    var serverConnectResponse: ServerConnectResponse
    try {
      thread {
        // TODO: this code is Android-only - need to make it run on the host too.
        try {
          if (!connectedLatch.await(1000, MILLISECONDS)) {
            // Maybe the process is in the background which can lead to hangs
            // TODO: why? (doze mode / app standby / background execution limits / idle / battery / etc)?
            // Seems to be related to "freezer". Here's a stack trace of our agent thread:
            // $ su 0 cat stack
            // [<0>] do_freezer_trap+0x50/0x8c
            // [<0>] get_signal+0x4c4/0x8a8
            // [<0>] do_notify_resume+0x134/0x340
            // [<0>] el0_svc+0x68/0xc4
            // [<0>] el0t_64_sync_handler+0x8c/0xfc
            // [<0>] el0t_64_sync+0x1a0/0x1a4
            val pkgPid = adbShellPb("pidof $pkg").stdout()
            val oomScoreAdj = adbShellPb("cat /proc/$pkgPid/oom_score_adj").stdout().trim().toInt()
            if (oomScoreAdj != 0) {
              logWarn {
                """
                  $pkg oom_score_adj is $oomScoreAdj which may have lead to a hang. Try foregrounding the app.
                """.trimIndent()
              }
            } else if (!connectedLatch.await(5000, MILLISECONDS)) {
              logWarn {
                """
                  $pkg appears to be hung. Try with --debug and/or --restart
                """.trimIndent()
              }
            }
          }
        } catch (e: Throwable) {
          logError { e.stackTraceToString() }
        }
      }

      serverConnectResponse = reader.readNext() as ServerConnectResponse
    } finally {
      connectedLatch.countDown()
    }

    logDebug { "connect response: $serverConnectResponse" }
    if (serverConnectResponse.stoicVersion != STOIC_VERSION) {
      throw Exception("Mismatched stoic versions: ${serverConnectResponse.stoicVersion} and $STOIC_VERSION")
    }

    val pluginModule = args.pluginModule
    val stagingPluginDexJar = resolveStagingPluginModule(pluginModule)

    // TODO: Sync plugins to pkg dir as needed, and let the server open them (no need to recopy each
    // time we run the same plugin again)

    // We can use relative paths because run-as-compat always starts in the pkg's home dir
    val pkgStoicRelativePluginDir = npsConnDir(".", serverConnectResponse.connId)

    // preserve the plugin dex jar name
    val pkgStoicRelativePluginDexJar = "$pkgStoicRelativePluginDir/$pluginModule.dex.jar"

    check(adbShellPb("""
      $runAsCompat $pkg mkdir -p stoic/$pkgStoicRelativePluginDir
      cat $stagingPluginDexJar | $runAsCompat $pkg sh -c 'cat > stoic/$pkgStoicRelativePluginDexJar'
      $runAsCompat $pkg chmod 444 stoic/$pkgStoicRelativePluginDexJar
    """.trimIndent()).inheritIO().start().waitFor() == 0)

    val startPlugin = StartPlugin(
      pluginJar = pkgStoicRelativePluginDexJar,
      pluginArgs = args.pluginArgs,
      minLogLevel = minLogLevel.name,
      env = args.pluginEnvVars,
    )
    logDebug { "startPlugin: $startPlugin" }
    writer.writeMessage(startPlugin)

    var exitCode = -1
    val countDownLatch = CountDownLatch(1)

    // process output from the plugin
    val outPumpThread = thread(name = "out-pump") {
      while (true) {
        when (val msg = reader.readNext()) {
          is PluginFinished -> {
            logDebug { "received PluginFinished: $msg" }
            exitCode = msg.exitCode
            countDownLatch.countDown()
            break
          }
          is StreamIO -> {
            when (msg.id) {
              STDOUT -> System.out.write(msg.buffer)
              STDERR -> System.err.write(msg.buffer)
              else -> throw IllegalStateException("Unexpected stream id: ${msg.id}")
            }
          }
          else -> throw IllegalStateException("Unexpected message: $msg")
        }
      }
    }
    outPumpThread.setUncaughtExceptionHandler { _, e ->
      // TODO: dump logcat?
      logError { e.stackTraceToString() }
      exitCode = 1
      countDownLatch.countDown()
    }

    val inPumpThread = thread (name = "in-pump") {
      val buffer = ByteArray(1024)
      while (true) {
        val numRead = System.`in`.read(buffer)
        val streamIO: Any = if (numRead < 0) {
          StreamClosed(STDIN)
        } else {
          StreamIO(STDIN, ByteArray(numRead).also {
            System.arraycopy(buffer, 0, it, 0, numRead)
          })
        }

        try {
          writer.writeMessage(streamIO)
        } catch (e: IOException) {
          // It's okay if the plugin ended when we were still trying to send input
          logDebug { e.stackTraceToString() }
        }
      }
    }
    inPumpThread.setUncaughtExceptionHandler { _, e ->
      // TODO: dump logcat?
      logError { e.stackTraceToString() }
      exitCode = 1
      countDownLatch.countDown()
      //exitProcess(1)
    }

    countDownLatch.await()
    val actualExitCode = exitCode
    // Trigger these to stop, if they haven't already
    inPumpThread.interrupt()
    outPumpThread.interrupt()

    return actualExitCode
  }

  abstract fun adbShellPb(cmd: String): ProcessBuilder

  open fun resolveStagingPluginModule(pluginModule: String): String {
    return "$stoicDeviceSyncPluginDir/$pluginModule.dex.jar"
  }

  abstract fun slowPath(): Int
}
