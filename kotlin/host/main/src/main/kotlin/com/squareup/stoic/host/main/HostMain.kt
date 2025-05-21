package com.squareup.stoic.host.main

import com.squareup.stoic.common.FailedExecException
import com.squareup.stoic.common.LogLevel.WARN
import com.squareup.stoic.common.MainParsedArgs
import com.squareup.stoic.common.PithyException
import com.squareup.stoic.common.PluginClient
import com.squareup.stoic.common.PluginParsedArgs
import com.squareup.stoic.common.LogLevel.DEBUG
import com.squareup.stoic.common.logBlock
import com.squareup.stoic.common.logDebug
import com.squareup.stoic.common.logError
import com.squareup.stoic.common.logVerbose
import com.squareup.stoic.common.minLogLevel
import com.squareup.stoic.common.runCommand
import com.squareup.stoic.common.serverSocketName
import com.squareup.stoic.common.stdout
import com.squareup.stoic.common.stoicDeviceDevJarDir
import com.squareup.stoic.common.stoicDeviceSyncPluginDir
import com.squareup.stoic.common.waitFor
import java.io.File

import java.lang.ProcessBuilder.Redirect
import java.net.Socket
import java.util.Locale
import kotlin.system.exitProcess


const val stoicDeviceDir = "/data/local/tmp/stoic"
const val stoicDeviceSyncDir = "$stoicDeviceDir/sync"

// adb shell does not forward env vars so we need to inject these
val stoicShellEnvVars = listOf(
  "PATH=\$PATH:$stoicDeviceSyncDir/bin",
  "STOIC_DEVICE_SYNC_DIR=$stoicDeviceSyncDir",
)

val nonInteractiveShellCmdArgs = listOf("adb", "shell", "-T") + stoicShellEnvVars


lateinit var stoicHostUsrConfigDir: String
lateinit var stoicHostUsrSyncDir: String
lateinit var stoicHostUsrPluginSrcDir: String

lateinit var stoicReleaseDir: String
lateinit var stoicHostDir: String
lateinit var stoicHostScriptDir: String
lateinit var stoicHostCoreSyncDir: String

fun main(rawArgs: Array<String>) {
  //System.err.println("start of HostMain.main")

  try {
    exitProcess(wrappedMain(rawArgs))
  } catch (e: PithyException) {
    // If we have a pithy message to display to the user, we'll display just that message
    // (unless debug logging is enabled) and then exit with status code 1.
    logDebug { e.stackTraceToString() }
    System.err.println(e.pithyMsg)
    exitProcess(e.exitCode)
  } catch (e: Exception) {
    // We don't have a pithy message
    logError { e.stackTraceToString() }
    exitProcess(1)
  }
}

fun wrappedMain(rawArgs: Array<String>): Int {
  minLogLevel = WARN

  // This is injected by the stoic shell script
  stoicReleaseDir = rawArgs[0]

  stoicHostScriptDir = "$stoicReleaseDir/script"
  stoicHostCoreSyncDir = "$stoicReleaseDir/sync"

  stoicHostUsrConfigDir = System.getenv("STOIC_CONFIG") ?: "${System.getenv("HOME")}/.config/stoic"
  stoicHostUsrSyncDir = "$stoicHostUsrConfigDir/sync"
  stoicHostUsrPluginSrcDir = "$stoicHostUsrConfigDir/plugin"

  // All remaining args are real args passed by the user
  val stoicArgs = rawArgs.drop(1).toTypedArray()
  val mainParsedArgs = MainParsedArgs.parse(stoicArgs)
  val androidSerial = System.getenv("ANDROID_SERIAL")
  return when (mainParsedArgs.command) {
    "shell" -> runShell(mainParsedArgs.stoicArgs, mainParsedArgs.commandArgs)
    "rsync" -> runRsync(mainParsedArgs.stoicArgs, mainParsedArgs.commandArgs)
    "shebang" -> throw PithyException("TODO")
    "exec" -> throw PithyException("TODO")
    "version" -> runVersion(mainParsedArgs.stoicArgs, mainParsedArgs.commandArgs)
    "setup" -> runSetup(mainParsedArgs.stoicArgs, mainParsedArgs.commandArgs)
    else -> runPlugin(mainParsedArgs)
  }
}

fun runPlugin(mainParsedArgs: MainParsedArgs): Int {
  val args = PluginParsedArgs.parse(mainParsedArgs)
  val pluginJar = resolvePluginModule(args.pluginModule)

  if (!args.restartApp) {
    // `adb forward`-powered fast path
    val serverSocketName = serverSocketName(args.pkg)
    val portStr = ProcessBuilder(
      "adb", "forward", "tcp:0", "localabstract:$serverSocketName"
    ).stdout()
    try {
      Socket("localhost", portStr.toInt()).use {
        val client = PluginClient(pluginJar, args, it.inputStream, it.outputStream)
        return client.handle()
      }
    } catch (e: Exception) {
      logDebug { "Failed host fast path: ${e.stackTraceToString()}"}
      // Fall through to slow-path
    } finally {
      ProcessBuilder("adb", "forward", "--remove", portStr)
    }
  }

  // slow-path
  syncDevice()

  // TODO: other stoic args
  var stoicArgs = mutableListOf("--log", minLogLevel.level.toString(), "--pkg", args.pkg)
  if (args.restartApp) {
    stoicArgs += listOf("--restart")
  }

  // Or maybe we provide a `stoic exec` that will delegate to a shell script which will use printf
  // to escape args correctly

  return ProcessBuilder(
    "adb",
    "shell",
    shellEscapeCmd(listOf("$stoicDeviceSyncDir/bin/stoic") + stoicArgs + listOf(args.pluginModule) + args.pluginArgs)
  ).inheritIO().start().waitFor()

}

fun runSetup(stoicArgs: List<String>, commandArgs: List<String>): Int {
  // Maybe we store the canonical version in Kotlin eventually.
  // Maybe build.sh can be replaced with Kotlin
  val buildToolsVersion = readUtilKey("stoic_build_tools_version")
  val targetApiLevel = readUtilKey("stoic_target_api_level")
  checkRequiredSdkPackages(
    "build-tools;$buildToolsVersion",
    "platforms;android-$targetApiLevel")

  ProcessBuilder("mkdir", "-p", stoicHostUsrSyncDir).inheritIO().waitFor(0)
  ProcessBuilder(
    "rsync",
    "--archive",
    "--ignore-existing",
    "$stoicReleaseDir/template/usr_config/",
    "$stoicHostUsrConfigDir/")
    .inheritIO().waitFor(0)

  ProcessBuilder("rsync", "$stoicReleaseDir/jar/stoic-android-plugin-sdk.jar", "$stoicHostUsrPluginSrcDir/lib/")
    .inheritIO().waitFor(0)
  ProcessBuilder("rsync", "$stoicReleaseDir/jar/stoic-android-plugin-sdk-sources.jar", "$stoicHostUsrPluginSrcDir/lib/")
    .inheritIO().waitFor(0)

  println("""
    Setup complete!
    
    Complete the following tutorial steps to familiarize yourself with Stoic:
    1. Connect an Android device (if you haven't done so already)
    2. Run `stoic helloworld` from the command-line
    3. Run `stoic scratch` from the command-line, then open it up in Android Studio and play around
       a) Android Studio -> File -> Open
       b) Cmd+Shift+G
       c) ~/.config/stoic/plugin
       d) Open
       e) Project -> plugin -> scratch -> Main.kt
       f) Make some edits and save
       g) Run `stoic scratch` again.
    4. Run `stoic shell`
       a) Add configuration/utilities to ~/.config/stoic/sync and it will be available on any device
          you `stoic shell` into.
    5. Run `stoic --pkg *package* appexitinfo`, replacing `*package*` with your own Android app.
  """.trimIndent())
  return 0
}

fun readUtilKey(key: String): String {
  return ProcessBuilder("sh", "-c", "source $stoicReleaseDir/script/util.sh; echo $$key")
    .inheritIO().stdout()
}

// function check_required {
//     # Check for required packages
//     sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
//     sdk_packages="$("$sdkmanager" --list_installed 2>/dev/null | awk '{print $1}')"
//     missing=()
//     for required in "$@"; do
//         if ! echo "$sdk_packages" | grep "$required" >/dev/null; then
//             missing+=("$required")
//         fi
//     done
//     if [ ${#missing[@]} -gt 0 ]; then
//         echo "stoic requires Android SDK package ${missing[*]}"
//         echo "Okay to install? (will run '$sdkmanager ${missing[*]}')"
//         read -r -p "Y/n: " choice
//         case "$(echo "$choice" | tr '[:upper:]' '[:lower:]')" in
//           n*)
//             exit 1
//             ;;
//           *)
//             $sdkmanager "${missing[@]}"
//             ;;
//         esac
//     fi
// }
fun checkRequiredSdkPackages(vararg required: String) {
  val androidHome = System.getenv("ANDROID_HOME")
    ?: throw PithyException("Please set ANDROID_HOME env var.")
  val sdkManager = "$androidHome/cmdline-tools/latest/bin/sdkmanager"
  val sdkPackages = ProcessBuilder(
    listOf("sh", "-c", "$sdkManager --list_installed 2>/dev/null | awk '{print $1}'")
  )
    .inheritIO()
    .stdout()
    .split("\n")

  val missing = required.filter { !sdkPackages.contains(it) }
  if (missing.isNotEmpty()) {
    val missingStr = missing.joinToString(" ")
    System.err.println("Stoic requires the following Android SDK Packages: $missingStr")
    System.err.println("Okay to install? (will run '$sdkManager $missingStr'")
    System.err.println("Y/n? ")
    val input = readln()
    when (input.lowercase(Locale.getDefault())) {
      "y", "" -> Unit
      else -> throw PithyException("aborted setup")
    }

    ProcessBuilder(sdkManager, missingStr).inheritIO().waitFor()
  }
}

fun runVersion(stoicArgs: List<String>, commandArgs: List<String>): Int {
  runCommand(listOf("""
    source $stoicReleaseDir/script/util.sh
    echo ${'$'}stoic_version
  """.trimIndent()), shell = true, inheritIO = true)

  return 0
}

fun runShell(stoicArgs: List<String>, commandArgs: List<String>): Int {
  if (stoicArgs.isNotEmpty()) {
    throw PithyException("stoic shell - unrecognized options: $stoicArgs")
  }
  syncDevice()
  var forceInteractive = false
  var forceNonInteractive = false
  var i = -1
  while (++i < commandArgs.size) {
    val arg = commandArgs[i]
    if (!arg.startsWith("-")) {
      break
    }

    i += 1
    when (arg) {
      "-t" -> forceInteractive = true
      "-T" -> forceNonInteractive = true
      else -> throw PithyException("stoic shell - unrecognized option: $arg")
    }
  }

  // TODO: need to also take into account whether stdin is a tty
  // Support: echo ls | stoic shell
  val shellArgs = commandArgs.drop(i)
  val interactive = if (forceInteractive) {
    true
  } else if (forceNonInteractive) {
    false
  } else if (shellArgs.isEmpty()) {
    true
  } else {
    false
  }

  try {
    if (interactive) {
      // TODO: Suitable error message if it doesn't exist
      // STOIC_DEVICE_SYNC_DIR="$stoic_device_sync_dir" sh ~/.config/stoic/interactive-shell.sh
      runCommand(
        listOf("sh", "$stoicHostUsrConfigDir/interactive-shell.sh"),
        envOverrides = mapOf("STOIC_DEVICE_SYNC_DIR" to stoicDeviceSyncDir),
        inheritIO = true
      )
    } else if (shellArgs.isNotEmpty()) {
      // TODO: optional config for non-interactive-shell
      runCommand(
        nonInteractiveShellCmdArgs + shellArgs,
        inheritIO = true
      )
    } else {
      val echos = stoicShellEnvVars.joinToString("\n        ") {
        "echo $it"
      }
      val cmd = """
        {
          $echos
          cat
        } | adb shell -T
      """.trimIndent()

      // We are asked for a non-interactive shell but no arguments
      // This means that stdin is providing a stream of commands to execute
      // So we prepend the stream with the updated PATH and send that to the command
      runCommand(listOf(cmd), inheritIO = true, shell = true)
    }

    return 0
  } catch (e: FailedExecException) {
    logDebug { e.stackTraceToString() }
    return e.exitCode
  }
}

// TODO: support --root option to run as root
// TODO: arsync-wrapper should fix ownership/permissions when run as root without --root
fun runRsync(stoicArgs: List<String>, commandArgs: List<String>): Int {
  if (stoicArgs.isNotEmpty()) {
    throw PithyException("stoic rsync - unrecognized options: $stoicArgs")
  }

  try {
    arsync(*commandArgs.toTypedArray())
    return 0
  } catch (e: FailedExecException) {
    logDebug { e.stackTraceToString() }
    return e.exitCode
  }
}

// This version of arsync never pushes as root - TODO: allow this as an option
fun arsync(vararg args: String) {
  val binDir = "$stoicDeviceSyncDir/bin"
  val adbRsyncPath = "$binDir/rsync"
  val devNullInput = Redirect.from(File("/dev/null"))
  // Fetch the information we need in a single `adb shell`
  // We intentionally avoid using 1 to represent a value because that often indicates other errors
  val exitCodeCmd = """
    uid=$(id -u)
    [ -e $adbRsyncPath ]
    exit $(( ( (uid == 0) << 1) | ($? << 2) ))
  """.trimIndent()
  logDebug { "exitCodeCmd: '$exitCodeCmd'"}
  val exitCode = ProcessBuilder(listOf("adb", "shell", exitCodeCmd))
    .inheritIO()
    .redirectInput(devNullInput)
    .start().waitFor()
  val isRoot = (exitCode shr 1) and 1 != 0
  val missingRsync= ((exitCode) shr 2) and 1 != 0

  // Sanity check the result
  if ((exitCode and 1) != 0 || (exitCode shr 3) != 0) {
    logError { "Unexpected exit code: $exitCode" }
  } else {
    logDebug { "rsync test exitcode: $exitCode - isRoot=$isRoot, missingRsync=$missingRsync" }
  }

  if (missingRsync) {
    if (isRoot) {
      check(0 == ProcessBuilder(listOf("adb", "push", "$stoicReleaseDir/sync/bin/rsync", "/data/local/tmp"))
        .inheritIO()
        .redirectInput(devNullInput)
        .start()
        .waitFor())
      check(0 == ProcessBuilder(
        listOf( "adb", "shell", """
          su shell mkdir -p $binDir 
          mv /data/local/tmp/rsync $binDir
          chown shell:shell $adbRsyncPath
        """.trimIndent()))
          .inheritIO()
        .redirectInput(devNullInput)
        .start()
        .waitFor())
    } else {
      check(0 == ProcessBuilder(
        listOf("adb", "shell", "mkdir -p $binDir"))
        .inheritIO()
        .redirectInput(devNullInput)
        .start()
        .waitFor())
      check(0 == ProcessBuilder(
        listOf("adb", "push", "$stoicReleaseDir/sync/bin/rsync", adbRsyncPath))
        .inheritIO()
        .redirectInput(devNullInput)
        .redirectOutput(Redirect.DISCARD)
        .start()
        .waitFor())
    }
  }

  // Need a special wrapper to avoid pushing as root
  val wrapper = if (isRoot) {
    "$stoicHostScriptDir/arsync-unroot-wrapper.sh"
  } else {
    "$stoicHostScriptDir/arsync-wrapper.sh"
  }

  // I observe hangs with large files if I don't pass --blocking-io
  val arsyncCmd = listOf("rsync", "--blocking-io", "--rsh=sh $wrapper") + args
  logDebug { "$arsyncCmd" }
  runCommand(arsyncCmd, inheritIO = true)
}

fun syncDevice() {
  arsync("--archive", "--delete", "$stoicHostCoreSyncDir/", "$stoicHostUsrSyncDir/", "adb:$stoicDeviceSyncDir/")

  // We remove write permissions to stop people from accidentally writing to files that will be
  // subsequently overwritten by the next sync
  // For better latency we do this in the background
  ProcessBuilder(listOf("adb", "shell", "chmod -R a-w $stoicDeviceSyncDir/"))
    .start()
}

fun shellEscapeCmd(cmdArgs: List<String>): String {
  return if (cmdArgs.isEmpty()) {
    ""
  } else {
    return ProcessBuilder(listOf("bash", "-c", """ printf " %q" "$@" """, "stoic") + cmdArgs).stdout().drop(0)
  }
}

fun resolvePluginModule(pluginModule: String): String? {
  logDebug { "Attempting to resolve '$pluginModule'" }
  val usrPluginSrcDir = "$stoicHostUsrPluginSrcDir/$pluginModule"
  val pluginDexJar = "$pluginModule.dex.jar"
  if (File(usrPluginSrcDir).exists()) {
    logBlock(DEBUG, { "Building $usrPluginSrcDir/$pluginModule" }) {
      // TODO: In the future, we should allow building a simple jar and stoic handles packaging it
      // into a dex.jar, as needed
      ProcessBuilder("./gradlew", "--quiet", ":$pluginModule:dexJar")
        .inheritIO()
        .directory(File(stoicHostUsrPluginSrcDir))
        .waitFor(0)
      ProcessBuilder("adb", "shell", "mkdir", "-p", stoicDeviceDevJarDir)

      return "$stoicHostUsrPluginSrcDir/$pluginModule/build/libs/$pluginDexJar"
    }
  }

  logDebug { "$usrPluginSrcDir does not exist - falling back to prebuilt locations." }

  val usrPluginDexJar = File("$stoicHostUsrSyncDir/plugins/$pluginDexJar")
  if (usrPluginDexJar.exists()) {
    return usrPluginDexJar.canonicalPath
  }
  val corePluginDexJar = File("$stoicHostCoreSyncDir/plugins/$pluginDexJar")
  if (corePluginDexJar.exists()) {
    return corePluginDexJar.canonicalPath
  }

  return null
}
