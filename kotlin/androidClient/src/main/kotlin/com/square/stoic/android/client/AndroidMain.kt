package com.square.stoic.android.client

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.AttributionSource
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.UserHandle
import android.util.Log
import com.square.stoic.common.JvmtiAttachOptions
import com.square.stoic.common.LogLevel.DEBUG
import com.square.stoic.common.LogLevel.WARN
import com.square.stoic.common.MainParsedArgs
import com.square.stoic.common.PithyException
import com.square.stoic.common.PluginParsedArgs
import com.square.stoic.common.PluginClient
import com.square.stoic.common.STOIC_PROTOCOL_VERSION
import com.square.stoic.common.logBlock
import com.square.stoic.common.logDebug
import com.square.stoic.common.logError
import com.square.stoic.common.logInfo
import com.square.stoic.common.logVerbose
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
import com.square.stoic.common.stoicExamplePkg
import com.square.stoic.common.waitSocketName
import kotlinx.serialization.json.Json
import java.io.File
import java.lang.ProcessBuilder.Redirect
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlinx.serialization.encodeToString
import java.util.concurrent.TimeUnit

val seLinuxViolationDetector = SELinuxViolationDetector()

fun main(args: Array<String>) {
  logVerbose { "start of AndroidMain.main" }

  val exitCode = try {
    wrappedMain(args)
  } catch (e: PithyException) {
    // If we have a pithy message to display to the user, we'll display just that message
    // (unless debug logging is enabled) and then exit with status code 1.
    logDebug { e.stackTraceToString() }
    System.err.println(e.pithyMsg)
    seLinuxViolationDetector.showViolations()
    e.exitCode
  } catch (e: Throwable) {
    // We don't have a pithy message
    logError { e.stackTraceToString() }
    seLinuxViolationDetector.showViolations()
    1
  } finally {
    // We need to stop the violation detector thread so the process can end
    seLinuxViolationDetector.stop()
  }

//Thread.getAllStackTraces().forEach {
//  //val thread = it.key
//  val stack = it.value
//  Throwable().also { throwable ->
//    throwable.stackTrace = stack
//    logDebug { throwable.stackTraceToString() }
//  }
//}
  logDebug { "Calling exitProcess($exitCode)" }
  exitProcess(exitCode)
}

fun wrappedMain(rawArgs: Array<String>): Int {
  minLogLevel = WARN

  Log.i("stoic", "hallo from wrappedMain")

  val mainParsedArgs = MainParsedArgs.parse(rawArgs)
  val args = PluginParsedArgs.parse(mainParsedArgs)
  val pkg = args.pkg
  if (mainParsedArgs.command == "shell") {
    throw PithyException("stoic shell from Android not yet supported")
  }

  val matches = runCommand(listOf("pm", "list", "package", pkg)).split("\n")
  if (!matches.contains("package:$pkg")) {
    if (pkg == stoicExamplePkg) {
      logInfo { "$stoicExamplePkg appears to not be installed - installing now." }

      // We always install the example pkg if it's requested
      runCommand(
        listOf("pm", "install", "apk/exampleapp-debug.apk"),
        directory = stoicDeviceSyncDir,
        redirectOutput = Redirect.to(File("/dev/null"))
      )
    } else {
      throw PithyException("$pkg not found")
    }
  }

  val pluginDexJar = resolveDexJar(args)

  if (isDebuggable(args.pkg)) {
    logDebug { "path for debuggable apks" }
    if (!args.restartApp) {
      try {
        return fastPath(pluginDexJar, args)
      } catch (e: Exception) {
        logInfo { "Plugin fast-path failed. Falling back to plugin slow-path." }
        logDebug { e.stackTraceToString() }
      }
    }

    return slowPath(pluginDexJar, args)

  } else {
    logDebug { "path for non-debuggable apks" }
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

    val providerName = "${args.pkg}.stoic"
    val bundle = callContentProvider(providerName, "open_standard", null, Bundle())
    if (DEBUG.isLoggable()) {
      logDebug { "bundle: $bundle" }
      bundle?.keySet()?.forEach {
        logDebug { "key: $it" }
        logDebug { "value: ${bundle.get(it)}" }
      }
    }
    bundle!!
    val socket = bundle.getParcelable("socket", ParcelFileDescriptor::class.java)
    ParcelFileDescriptor.AutoCloseOutputStream(socket).use { output ->
      ParcelFileDescriptor.AutoCloseInputStream(socket).use { input ->
        val client = PluginClient(pluginDexJar, args, input, output)
        return client.handle()
      }
    }
  }
}

/**
 * Returns whether or not the pkg is debuggable
 * (this returns false for non-debuggable pkg even if the device is rooted)
 */
fun isDebuggable(pkg: String): Boolean {
  return ProcessBuilder(listOf("run-as", pkg, "true"))
    .redirectError(File("/dev/null"))
    .start()
    .waitFor() == 0
}

@SuppressLint("PrivateApi")
fun callContentProvider(
  providerName: String, method: String, arg: String?, bundle: Bundle
): Bundle? {
  // The following code is adapted from
  // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/cmds/content/src/com/android/commands/content/Content.java
  // This uses private APIs.

  // To avoid private APIs we could use a separate debuggable apk with an exported ContentProvider
  // to act as a bridge. Since the bridge apk is debuggable, it could host a LocalSocketServer and
  // we'd be able to connect to that. So we'd connect to the bridge apk over a LocalSocket, then
  // ask it to query the ContentProvider

  val userId = UserHandle::class.java.getField("USER_SYSTEM").get(null)

  val activityManager = ActivityManager::class.java.getMethod("getService").invoke(null)
  var provider: Any? = null
  val token = Binder()
  try {
    val holder = Class.forName("android.app.IActivityManager")
      .getMethod(
        "getContentProviderExternal",
        String::class.java,
        Int::class.java,
        IBinder::class.java,
        String::class.java
      ).invoke(
        activityManager, providerName, userId, token, "*cmd*"
      )
    if (holder == null) {
      throw IllegalStateException("Could not find provider: $providerName")
    }
    provider = Class.forName("android.app.ContentProviderHolder")
      .getField("provider")
      .get(holder)
    val attributionSourceCtor = AttributionSource::class.java.getConstructor(
      Int::class.java, String::class.java, String::class.java
    )
    val attributionSource = attributionSourceCtor.newInstance(
      Binder.getCallingUid(), resolveCallingPackage(), null
    ) as AttributionSource
    return Class.forName("android.content.IContentProvider")
      .getMethod(
        "call",
        AttributionSource::class.java,
        String::class.java,
        String::class.java,
        String::class.java,
        Bundle::class.java
      ).invoke(
        provider, attributionSource, providerName, method, arg, bundle
      ) as Bundle?
  } finally {
    if (provider != null) {
      Class.forName("android.app.IActivityManager")
        .getMethod(
          "removeContentProviderExternalAsUser",
          String::class.java,
          IBinder::class.java,
          Int::class.java
        ).invoke(
          activityManager, providerName, token, userId
        )
    }
  }
}

fun resolveCallingPackage(): String? {
  when (android.os.Process.myUid()) {
    android.os.Process.ROOT_UID -> {
      return "root"
    }
    android.os.Process.SHELL_UID -> {
      return "com.android.shell";
    }
    else -> {
      return null
    }
  }
}

fun resolveDexJar(pluginParsedArgs: PluginParsedArgs): String? {
  val pluginName = pluginParsedArgs.pluginModule
  val devDexJar = "$stoicDeviceDevJarDir/$pluginName.dex.jar"
  if (File(devDexJar).exists()) {
    return devDexJar
  }

  val stagingPluginDexJar = "$stoicDeviceSyncPluginDir/$pluginName.dex.jar"
  if (File(stagingPluginDexJar).exists()) {
    return stagingPluginDexJar
  }

  return null
}

private fun startPkg(pkg: String): String {
  monkey(pkg)

  // Wait for the process to start
  var i = 0
  while (true) {
    i += 1
    try {
      val pid = runCommand(listOf("pidof", pkg))
      logDebug { "$pkg is running: pid=$pid" }
      return pid
    } catch (e: Exception) {
      logDebug { "$pkg not yet running, sleeping 10ms..." }

      // Wait up to 5s, but with warnings after 1s
      if (i >= 500) {
        // Probably something else is very wrong...
        throw Exception("Failed to start within 5s", e)
      } else if (i % 100 == 0) {
        logWarn { "$pkg is slow to start (maybe it's stuck) - retrying monkey" }
        monkey(pkg)
      }

      Thread.sleep(10)
    }
  }
}

private fun monkey(pkg: String) {
  // We specify `--pct-syskeys 0` to work on emulators without physical keys
  // See https://stackoverflow.com/a/46935037 for details
  runCommand(
    listOf("monkey", "--pct-syskeys", "0", "-p", pkg, "1"),
    redirectOutputAndError = Redirect.to(File("/dev/null"))
  )
}

private fun fastPath(pluginDexJar: String?, args: PluginParsedArgs): Int {
  val pkg = args.pkg
  val pkgSocat = "./stoic/socat"  // We start in home
  val serverAddress = serverSocketName(pkg)
  val pid = ProcessBuilder("pidof", pkg).stdout(expectedExitCode = null)
  val pb = ProcessBuilder(runAsCompat, pkg, pkgSocat, "-", "ABSTRACT-CONNECT:$serverAddress")

  // If our log level is DEBUG (or VERBOSE) then we show stderr. Otherwise stderr will by default
  // go to pipe and not be seen at all.
  if (minLogLevel <= DEBUG) {
    pb.redirectError(Redirect.INHERIT)
  }

  return attemptPlugin(pluginDexJar, args, pid, pb.start())
}


private fun slowPath(pluginDexJar: String?, args: PluginParsedArgs): Int {
  logBlock(DEBUG, { "AndroidPluginClient.slowPath" }) {
    val pkg = args.pkg
    // run-as doesn't work with non-debuggable processes, even with root. So we provide
    // run-as-compat that does work with non-debuggable processes, provided we have root.
    // TODO: there is actually a lot of details to make this really work. I think the key thing
    // is adding --runtime-flags=256 to init.zygote64.rc but also perhaps
    // setprop persist.debug.dalvik.vm.jdwp.enabled 1
    // is required. I'm not sure whether --runtime-flags=256 implies jdwp enabled...

    val pkgDir = runCommand(listOf(runAsCompat, args.pkg, "printenv", "HOME"))
    val pkgStoicDir = "$pkgDir/stoic"
    val pkgSocat = "$pkgStoicDir/socat"
    logDebug { "pkgDir=$pkgDir" }

    val androidPkgPid = if (args.restartApp) {
      runCommand(listOf("am", "force-stop", pkg))
      startPkg(pkg)
    } else {
      try {
        runCommand(listOf("pidof", pkg))
      } catch (e: Exception) {
        if (args.startIfNeeded) {
          startPkg(pkg)
        } else {
          val message = """
              $pkg not running. Use --restart (or -r for short) to force it to restart
              (this option will stop the process if it's already running)
              
              stoic --restart ...
            """.trimIndent()
          throw PithyException(message, e = e)
        }
      }
    }

    // We only get here once PluginClient has already tried the fast path. So we start the server
    // and try again
    startAndroidServer(args, pkgStoicDir, pkgSocat, androidPkgPid)
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
      return attemptPlugin(pluginDexJar, args, androidPkgPid, process)
    } catch (e: Throwable) {
      logError { e.stackTraceToString() }
      throw e
    } finally {
      process?.destroyForcibly()
    }
  }
}

private fun startAndroidServer(
  args: PluginParsedArgs,
  pkgStoicDir: String,
  pkgSocat: String,
  androidPkgPid: String
) {
  val pkg = args.pkg

  // Double-verify stoic isn't already loaded
  logDebug { "Double-verifying that stoic.so isn't already loaded into the debuggee" }
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
  // NOTE: in theory we don't need to delete the entire directory, but it probably doesn't affect
  //   perf all that much - the slow part is attaching via JVMTI
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
      val reader = process.inputStream.bufferedReader()
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
    stoicVersion = STOIC_PROTOCOL_VERSION,
  )
  logDebug { "attach options: $options" }

  // We have to specify a template argument to mktemp to avoid errors on some
  // older versions of Android.
  val optionsJsonStagingPath = runCommand(listOf("mktemp", "/data/local/tmp/XXXXXXXX"))
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

    val logcatReader = logcatProcess.inputStream.bufferedReader()
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

private fun attemptPlugin(
  pluginDexJar: String?,
  args: PluginParsedArgs,
  pid: String,
  start: Process
): Int {
  start.outputStream.use { output ->
    start.inputStream.use { input ->
      val client = PluginClient(pluginDexJar, args, input, output)
      return client.handle()
    }
  }
}

