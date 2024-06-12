package com.square.stoic.android.client

import com.square.stoic.common.PluginClient
import com.square.stoic.common.JvmtiAttachOptions
import com.square.stoic.common.LogLevel.DEBUG
import com.square.stoic.common.PithyException
import com.square.stoic.common.PluginParsedArgs
import com.square.stoic.common.STOIC_VERSION
import com.square.stoic.common.logBlock
import com.square.stoic.common.logDebug
import com.square.stoic.common.logError
import com.square.stoic.common.logInfo
import com.square.stoic.common.logWarn
import com.square.stoic.common.minLogLevel
import com.square.stoic.common.optionsJsonFromStoicDir
import com.square.stoic.common.runAsCompat
import com.square.stoic.common.runCommand
import com.square.stoic.common.serverSocketName
import com.square.stoic.common.stdout
import com.square.stoic.common.stoicDeviceDevJarDir
import com.square.stoic.common.stoicDeviceSyncDir
import com.square.stoic.common.stoicDeviceSyncPluginDir
import com.square.stoic.common.waitSocketName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.EOFException
import java.io.File
import java.lang.ProcessBuilder.Redirect
import java.net.ConnectException
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AndroidPluginClient(args: PluginParsedArgs) : PluginClient(args) {
  private lateinit var pkgDir: String
  private lateinit var pkgStoicDir: String
  private lateinit var pkgSocat: String

  override fun slowPath(): Int {
    logBlock(DEBUG, { "AndroidPluginClient.slowPath" }) {
      val pkg = args.pkg
      // run-as doesn't work with non-debuggable processes, even with root. So we provide
      // run-as-compat that does work with non-debuggable processes, provided we have root.
      // TODO: there is actually a lot of details to make this really work. I think the key thing
      // is adding --runtime-flags=256 to init.zygote64.rc but also perhaps
      // setprop persist.debug.dalvik.vm.jdwp.enabled 1
      // is required. I'm not sure whether --runtime-flags=256 implies jdwp enabled...

      pkgDir = runCommand(listOf(runAsCompat, args.pkg, "printenv", "HOME"))
      pkgStoicDir = "$pkgDir/stoic"
      pkgSocat = "$pkgStoicDir/socat"
      logDebug { "pkgDir=$pkgDir" }

      if (args.restartApp) {
        runCommand(listOf("am", "force-stop", pkg))
        startPkg(pkg)
      } else if (args.startIfNeeded) {
        try {
          runCommand(listOf("pidof", pkg))
        } catch (e: Exception) {
          startPkg(pkg)
        }
      }

      // We only get here once PluginClient has already tried the fast path. So we start the server
      // and try again
      startAndroidServer()
      val pid = adbShellPb("pidof $pkg").stdout(expectedExitCode = null)
      val socatVerbosity = if (minLogLevel <= DEBUG) { listOf("-dd") } else { listOf() }
      var process: Process? = null
      try {
        val serverAddress = serverSocketName(pkg)
        val builder = ProcessBuilder(
          listOf(runAsCompat, pkg, pkgSocat) + socatVerbosity + listOf("-", "ABSTRACT-CONNECT:$serverAddress"),
        )

        // We don't expect any errors this time around
        builder.redirectError(Redirect.INHERIT)

        logDebug { "input redirect: ${builder.redirectInput()}" }
        logDebug { "output redirect: ${builder.redirectOutput()}" }
        process = builder.start()
        return attemptPlugin(pkg, pid, process)
      } catch (e: Throwable) {
        logError { e.stackTraceToString() }
        throw e
      } finally {
        process?.destroyForcibly()
      }
    }
  }

  private fun startPkg(pkg: String) {
    runCommand(
      listOf("monkey", "-p", pkg, "1"),
      redirectOutputAndError = Redirect.to(File("/dev/null"))
    )

    // Wait for the process to start
    var i = 0
    while (true) {
      i += 1
      try {
        runCommand(listOf("pidof", pkg))
        logDebug { "$pkg is running" }
        break
      } catch (e: Exception) {
        logDebug { "$pkg not yet running, sleeping 10ms..." }

        // Wait up to 5s, but with warnings after 1s
        if (i >= 500) {
          // Probably something else is very wrong...
          throw Exception("Failed to start within 5s", e)
        } else if (i % 100 == 0) {
          logWarn { "$pkg is slow to start (maybe it's stuck) - retrying monkey" }
          runCommand(
            listOf("monkey", "-p", pkg, "1"), redirectOutputAndError = Redirect.to(File("/dev/null"))
          )
        }

        Thread.sleep(10)
      }
    }
  }

  private fun startAndroidServer() {
    val pkg = args.pkg

    // Double-verify stoic isn't already loaded
    logDebug { "Double-verifying that stoic.so isn't already loaded into the debuggee" }
    val androidPkgPid = try {
      runCommand(
        listOf("pidof", pkg)
      )
    } catch (e: Exception) {
      val message = """
        $pkg not running. Use --restart (or -r for short) to force it to restart
        (this option will stop the process if it's already running)
        
        stoic --restart ...
      """.trimIndent()
      throw PithyException(message, e = e)
    }

    try {
      runCommand(
        listOf("$runAsCompat \"$pkg\" cat /proc/$androidPkgPid/maps | grep 'stoic.so'"),
        inheritIO=true, shell=true, expectedExitCode=1,
      )
    } catch (e: Exception) {
      val message = """
        stoic already loaded - need to restart the process first
        consider:
          stoic --restart ...
      """.trimIndent()
      throw PithyException(message, e = e)
    }

    // Clean the slate
    logDebug { "cleaning the android pkg stoic slate" }

    // Note: this seems to trip up SELinux on some devices
    runCommand(
      listOf(runAsCompat, pkg, "rm", "-rf", pkgStoicDir),
    )

    runCommand(
      listOf(runAsCompat, pkg, "mkdir", pkgStoicDir),
    )

    val pkgSo = "$pkgStoicDir/stoic.so"

    // TODO: need to handle dex metadata - see
    // https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/content/pm/dex/DexMetadataHelper.java
    val pkgServerDexJar = "$pkgStoicDir/stoic.dex.jar"

    // Copy socat first so we can minimize the race condition and start listening right away
    runCommand(
      listOf("cat $stoicDeviceSyncDir/bin/socat | $runAsCompat $pkg sh -c 'cat > $pkgSocat'"),
      shell = true)
    runCommand(listOf(runAsCompat, pkg, "chmod", "777", pkgSocat))

    val didError = AtomicBoolean(false)
    val latch = CountDownLatch(1)
    logDebug { "Starting thread to listen to socat" }
    thread {
      logDebug { "Thread is listening for socat" }
      // Wait for the server to tell us its up with a 20 second timeout
      // 5 more seconds than the latch timeout below, so that one *should* be the meaningful one
      // But we have a timeout here too to make sure this process doesn't linger around
      val waitSocketAddr = waitSocketName(pkg)
      logDebug { "socat listening unix domain socket to wait for notification that server is ready for connect: $waitSocketAddr" }
      var process: Process? = null
      try {
        val builder = ProcessBuilder(
          listOf(runAsCompat, pkg, pkgSocat, /*"-dddd",*/ "ABSTRACT-LISTEN:$waitSocketAddr,accept-timeout=20", "-"),
        )
        builder.redirectError(Redirect.INHERIT)
        process = builder.start()
        val reader = process.inputStream.bufferedReader(UTF_8)
        var line: String? = null
        while (reader.readLine()?.also { line = it } != null) {
          logDebug { "stdout: $line" }
          logDebug { "Received output. Server is up" }
          break;
        }

        // TODO: really we should start socat with -dd and monitor stderr for `listening on AF=1`
        // and not attach-agent until we see that.

        //val reader = process.errorStream.bufferedReader(UTF_8)
        //var line: String? = null
        //while (reader.readLine()?.also { line = it } != null) {
        //  println("ERROR: $line")

        //  didError.set(true)
        //}

        logDebug { "socat done" }
      } catch (e: Throwable) {
        didError.set(true)
        logError { e.stackTraceToString() }
        throw e
      } finally {
        process?.destroyForcibly()
        latch.countDown()
      }
    }

    // Copy but make the package the owner. TODO: Could we rsync? (that would require the rsync binary
    // to be copied over
    runCommand(
      listOf("cat $stoicDeviceSyncDir/stoic/stoic.so | $runAsCompat $pkg sh -c 'cat > $pkgSo'"),
      shell = true)
    runCommand(listOf(runAsCompat, pkg, "chmod", "444", pkgSo))
    runCommand(
      listOf("cat $stoicDeviceSyncDir/stoic/stoic.dex.jar | $runAsCompat $pkg sh -c 'cat > $pkgServerDexJar'"),
      shell = true)
    runCommand(listOf(runAsCompat, pkg, "chmod", "444", pkgServerDexJar))

    val options = JvmtiAttachOptions(
      stoicVersion = STOIC_VERSION,
    )
    logDebug { "attach options: $options" }

    val optionsJsonStagingPath = runCommand(listOf("mktemp"))
    logDebug { "optionsJsonHostPath: $optionsJsonStagingPath" }
    File(optionsJsonStagingPath).writeText(Json.encodeToString(options))
    val optionsJsonPkgPath = optionsJsonFromStoicDir(pkgStoicDir)
    runCommand(
      listOf("cat $optionsJsonStagingPath | $runAsCompat $pkg sh -c 'cat > $optionsJsonPkgPath'"),
      shell = true)


    // Monitor logcat from this point forward
    val pb = ProcessBuilder(listOf("logcat", "-T", "1", "--pid", androidPkgPid, "stoic:v *:w"))
    val logcatProcess = pb.start()
    try {
      // There is a race condition here. I started a socat server above asynchronously
      // If it hasn't started listening by the time the agent attaches, then there will be a problem
      runCommand(listOf("am", "attach-agent", pkg, "$pkgSo=$pkgStoicDir"), inheritIO = true)

      val logcatReader = logcatProcess.inputStream.bufferedReader(UTF_8)
      val logcatLines = mutableListOf<String>()
      thread {
        while (true) {
          val line = try {
            logcatReader.readLine()
          } catch (e: Throwable) {
            // Usually this means that we hit the timeout and we killed the logcat process
            // In rare circumstances it might mean that the logcat process died on its own
            logDebug { e.stackTraceToString() }
            break
          }

          if (line == null) {
            // Usually this means that we hit the timeout and we killed the logcat process
            // In rare circumstances it might mean that the logcat process died on its own
            logDebug { "logcat end-of-stream" }
            break
          }


          logcatLines.add(line)
          logDebug { line }
        }
      }

      // 10 seconds is enough maybe 95% of the time
      // Hopefully 15 seconds is enough to make it completely reliable
      logDebug { "Listening for server start" }
      if (!latch.await(15, TimeUnit.SECONDS)) {
        logError { logcatLines.joinToString("\n") }
        throw PithyException("Server start timed out")
      } else {
        logInfo { "Server started" }
      }
    } finally {
      logcatProcess.destroyForcibly()
    }
  }

  override fun adbShellPb(cmd: String): ProcessBuilder {
    logBlock(DEBUG, { "AndroidPluginClient.adbShell [$cmd]" }) {
      return ProcessBuilder(listOf("sh", "-c", cmd))
    }
  }

  override fun resolveStagingPluginModule(pluginModule: String): String {
    val devDexJar = "$stoicDeviceDevJarDir/$pluginModule.dex.jar"
    if (File(devDexJar).exists()) {
      return devDexJar
    }

    val stagingPluginDexJar = "$stoicDeviceSyncPluginDir/$pluginModule.dex.jar"
    if (File(stagingPluginDexJar).exists()) {
      return stagingPluginDexJar
    }

    throw PithyException("$pluginModule.dex.jar was not found within $stoicDeviceDevJarDir or $stoicDeviceSyncPluginDir")
  }
}