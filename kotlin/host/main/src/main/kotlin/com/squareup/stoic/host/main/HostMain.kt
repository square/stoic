package com.squareup.stoic.host.main

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.OptionDelegate
import com.github.ajalt.clikt.parameters.options.OptionWithValues
import com.github.ajalt.clikt.parameters.options.RawOption
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.nullableFlag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.pair
import com.github.ajalt.clikt.parameters.options.transformValues
import com.github.ajalt.clikt.parameters.options.versionOption
import com.squareup.stoic.bridge.StoicProperties
import com.squareup.stoic.common.FailedExecException
import com.squareup.stoic.common.LogLevel
import com.squareup.stoic.common.PithyException
import com.squareup.stoic.common.PluginClient
import com.squareup.stoic.common.PluginParsedArgs
import com.squareup.stoic.common.logBlock
import com.squareup.stoic.common.logDebug
import com.squareup.stoic.common.logError
import com.squareup.stoic.common.logInfo
import com.squareup.stoic.common.minLogLevel
import com.squareup.stoic.common.runCommand
import com.squareup.stoic.common.serverSocketName
import com.squareup.stoic.common.stdout
import com.squareup.stoic.common.stoicDeviceDevJarDir
import com.squareup.stoic.common.stoicDeviceSyncDir
import com.squareup.stoic.common.waitFor
import java.io.File
import java.io.FileFilter
import java.io.FilenameFilter

import java.lang.ProcessBuilder.Redirect
import java.net.Socket
import java.nio.file.Paths
import kotlin.system.exitProcess


var isGraal: Boolean = false

lateinit var stoicHostUsrConfigDir: String
lateinit var stoicHostUsrSyncDir: String
lateinit var stoicHostUsrPluginSrcDir: String

lateinit var stoicReleaseDir: String
lateinit var stoicHostScriptDir: String
lateinit var stoicHostCoreSyncDir: String

lateinit var adbSerial: String

class Entrypoint : CliktCommand(
  name = "stoic",
) {
  init {
    context { allowInterspersedArgs = false }
    versionOption(
      version = StoicProperties.STOIC_VERSION_NAME,
      names = setOf("-v", "--version")
    )
  }

  override val printHelpOnEmptyArgs = true
  override fun help(context: Context): String {
    return """
      Stoic communicates with processes running on Android devices.

      Stoic will attempt to attach to a process via jvmti and establish a unix-domain-socket server.
      If successful, stoic will communicate to the server to request that the specified plugin run,
      with any arguments that follow, connecting stdin/stdout/stderr between the plugin and the
      stoic process.

      e.g. stoic helloworld "this is one arg" "this is a second arg"

      Special functionality is available via `stoic tool <tool-name> <tool-args>` - for details, see
      `stoic tool --help`
    """.trimIndent()
  }

  // Track which options were explicitly set
  private val specifiedOptions = mutableSetOf<String>()

  fun verifyAllowedOption(vararg allowedOptions: String) {
    specifiedOptions.forEach {
      if (it !in allowedOptions) {
        throw CliktError("--$it not allowed in this context")
      }
    }
  }

  fun RawOption.trackableFlag(): OptionDelegate<Boolean> {
    val longestName = names.maxByOrNull { it.length }!!
    return nullableFlag()
      .transformValues(0..0) {
        specifiedOptions += longestName
        true
      }
      .default(false)
  }

  fun RawOption.trackableOption(): OptionWithValues<String?, String, String> {
    val longestName = names.maxByOrNull { it.length }!!
    return convert {
      specifiedOptions += longestName
      it
    }
  }

  val verbose by option(
    "--verbose",
    help = "enable verbose logging"
  ).trackableFlag()
  val debug by option(
    "--debug",
    help = "enable debug logging"
  ).trackableFlag()
  val info by option(
    "--info",
    help = "enable info logging"
  ).trackableFlag()

  val restartApp by option(
    "--restart",
    "--restart-app",
    "-r",
    help = "if it's already running, force restart the specified package"
  ).trackableFlag()
  val noStartIfNeeded by option(
    "--no-start-if-needed",
    help = """
      by default, stoic will start the specified package if it's not already running - this option
      disables that behavior
    """.trimIndent()
  ).trackableFlag()

  val androidSerial by option(
    "--android-serial",
    "--serial",
    "-s",
    help = "Use device with given serial (overrides \$ANDROID_SERIAL)"
  ).trackableOption()

  val pkg by option(
    "--package",
    "--pkg",
    "-p",
    help = "Specify the package of the process to connect to"
  ).trackableOption().default("com.squareup.stoic.demoapp.withoutsdk")

  val env by option(
    "--env",
    help = "Environment key=value pairs - plugins access these via stoic.getenv(...)"
  ).trackableOption().pair().multiple()

  val isDemo by option(
    "--demo",
    help = "limit plugin resolution to demo plugins"
  ).trackableFlag()
  val isBuiltin by option(
    "--builtin",
    "--built-in",
    help = "limit plugin resolution to builtin plugins"
  ).trackableFlag()
  val isUser by option(
    "--user",
    "--usr",
    help = "limit plugin resolution to user plugins"
  ).trackableFlag()

  val subcommand by argument(name = "plugin")
  val subcommandArgs by argument(name = "plugin-args").multiple()

  var demoAllowed = false
  var builtinAllowed = false
  var userAllowed = false

  fun resolveAllowed() {
    if (isDemo) {
      demoAllowed = true
    } else if (isBuiltin) {
      builtinAllowed = true
    } else if (isUser) {
      userAllowed = true
    } else {
      demoAllowed = true
      builtinAllowed = true
      userAllowed = true
    }
  }

  override fun run() {
    resolveAllowed()

    if (restartApp && noStartIfNeeded) {
      throw CliktError("--restart-app and --no-start-if-needed are mutually exclusive")
    }

    if (listOf(verbose, debug, info).count { it } > 1) {
      throw CliktError("--verbose and --debug are mutually exclusive")
    }

    if (verbose) {
      minLogLevel = LogLevel.VERBOSE
    } else if (debug) {
      minLogLevel = LogLevel.DEBUG
    } else if (info) {
      minLogLevel = LogLevel.INFO
    } else {
      minLogLevel = LogLevel.WARN
    }


    logDebug { "isGraal=$isGraal" }

    when (subcommand) {
      "tool" -> {
        runTool(this)
      }
      else -> {
        resolveAdbSerial(androidSerial)

        val exitCode = runPlugin(this)
        if (exitCode != 0) {
          throw PithyException(null, exitCode)
        }
      }
    }
  }
}

class ShellCommand(val entrypoint: Entrypoint) : CliktCommand(name = "shell") {
  init { context { allowInterspersedArgs = false } }
  override fun help(context: Context): String {
    return """
      like `adb shell` but syncs directories and initializes the shell env
 
      If it exists, $stoicHostCoreSyncDir will be synced to $stoicDeviceSyncDir
      If it exists, $stoicHostUsrConfigDir/shell.sh will be run to start the shell. You may
      reference the following environment variables in your shell.sh:
 
        STOIC_DEVICE_SYNC_DIR (this will be set to $stoicDeviceSyncDir)
        STOIC_TTY_OPTION (this will be set to one of -t/-tt/-T depending on the invocation of
        `stoic tool shell`)

    """.trimIndent()
  }

  val shellArgs by argument().multiple()

  val tty by option("--tty", "-t").flag()
  val forceTty by option("--force-tty", "-tt").flag()
  val disableTty by option("--disable-tty", "-T").flag()

  override fun run() {
    entrypoint.verifyAllowedOption("--verbose", "--debug", "--android-serial")
    resolveAdbSerial(entrypoint.androidSerial)

    if (listOf(tty, forceTty, disableTty).count { it } > 1) {
      throw CliktError("-t, -tt, and -T are mutually exclusive")
    }

    // We attempt to preserve the meaning of -t/-tt/-T from `adb shell`.
    // TODO: One small difference:
    //   `adb shell` only takes into account whether stdin is connected to a tty, whereas
    //   System.console() != null checks whether both stdin/stdout are connected to ttys.
    val ttyOption = if (forceTty) {
      "-tt"
    } else if (disableTty) {
      "-T"
    } else if (tty) {
      // Only use a tty if stdin is a tty
      if (System.console() != null) { "-tt" } else { "-T" }
    } else {
      if (shellArgs.isEmpty() && System.console() != null) { "-tt" } else { "-T" }
    }

    syncDevice()
    try {
      runCommand(
        listOf("sh", "$stoicHostUsrConfigDir/shell.sh") + shellArgs,
        envOverrides = mapOf(
          "ANDROID_SERIAL" to adbSerial,
          "STOIC_TTY_OPTION" to ttyOption,
          "STOIC_DEVICE_SYNC_DIR" to stoicDeviceSyncDir,
        ),
        inheritIO = true
      )
    } catch (e: FailedExecException) {
      logDebug { e.stackTraceToString() }

      // TODO: throw something and exit all in one place
      exitProcess(e.exitCode)
    }
  }
}

class RsyncCommand(val entrypoint: Entrypoint) : CoreCliktCommand(name = "rsync") {
  init {
    context {
      allowInterspersedArgs = false
    }
  }
  override val treatUnknownOptionsAsArgs = true
  override fun help(context: Context): String {
    return """
      rsync to/from an Android device over adb, where Android paths are prefixed with `adb:`

      `stoic tool rsync` is powered by actual rsync, so it supports the same options. Run
      `rsync --help` to see details.

      e.g. to sync a hypothetical docs directory from your laptop to your Android device:
      `stoic tool rsync --archive docs/ adb:/data/local/tmp/docs/`

      e.g. to sync a hypothetical docs directory from your Android device to your laptop:
      `stoic tool rsync --archive adb:/data/local/tmp/docs/ docs/`
    """.trimIndent()
  }

  val rsyncArgs by argument().multiple()

  override fun run() {
    entrypoint.verifyAllowedOption("--verbose", "--debug", "--android-serial")
    resolveAdbSerial(entrypoint.androidSerial)

    arsync(*rsyncArgs.toTypedArray())
  }
}

class SetupCommand(val entrypoint: Entrypoint) : CliktCommand(name = "setup") {
  override fun help(context: Context): String {
    return """
      initializes ~/.config/stoic
    """.trimIndent()
  }

  override fun run() {
    entrypoint.verifyAllowedOption("--verbose", "--debug")

    val buildToolsVersion = StoicProperties.ANDROID_BUILD_TOOLS_VERSION
    val targetApiLevel = StoicProperties.ANDROID_TARGET_SDK

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
      4. Run `stoic tool shell`
         a) Add configuration/utilities to ~/.config/stoic/sync and it will be available on any device
            you `stoic tool shell` into.
      5. Run `stoic --pkg *package* appexitinfo`, replacing `*package*` with your own Android app.
    """.trimIndent())
  }
}

class ToolCommand : CliktCommand(name = "stoic tool") {
  init {
    context { allowInterspersedArgs = false }
    versionOption(
      version = StoicProperties.STOIC_VERSION_NAME,
      names = setOf("-v", "--version")
    )
  }

  override fun help(context: Context): String {
    return """
      Run a special command - for details, see: `stoic tool <command-name> --help`
    """.trimIndent()
  }

  override fun run() {
    logDebug { "ToolCommand.run" }
  }
}

class ListCommand(val entrypoint: Entrypoint) : CliktCommand(name = "list") {
  override fun help(context: Context): String {
    return """
      List available stoic plugins
    """.trimIndent()
  }

  override fun run() {
    // TODO: need a way to identify which directories within ~/.config/stoic/plugin are actually
    //   plugins

    // TODO: Invoke builtin stoic-list to get list of builtin plugins

    val dexJarFilter = object: FileFilter {
      override fun accept(file: File): Boolean {
        return file.isFile && file.name.endsWith(".dex.jar")
      }
    }

    entrypoint.resolveAllowed()

    if (entrypoint.userAllowed) {
      val usrPrebuilts = File("$stoicHostUsrSyncDir/plugins").listFiles(dexJarFilter)!!
      usrPrebuilts.forEach {
        println(it.name.removeSuffix(".dex.jar"))
      }
    }

    if (entrypoint.demoAllowed) {
      val demoPrebuilts = File("$stoicHostCoreSyncDir/plugins").listFiles(dexJarFilter)!!
      demoPrebuilts.forEach {
        println(it.name.removeSuffix(".dex.jar"))
      }
    }
  }
}


fun main(rawArgs: Array<String>) {
  isGraal = System.getProperty("org.graalvm.nativeimage.imagecode") != null
  stoicReleaseDir = if (isGraal) {
    // This is how we find the release dir from the GraalVM-generated binary
    val pathToSelf = ProcessHandle.current().info().command().get()
    Paths.get(pathToSelf).toRealPath().parent.parent.toAbsolutePath().toString()
  } else {
    // This is how we find the release dir when normally (via a jar)
    val uri = Entrypoint::class.java.protectionDomain.codeSource.location.toURI()
    File(uri).toPath().parent.parent.toAbsolutePath().toString()
  }

  minLogLevel = LogLevel.WARN

  stoicHostScriptDir = "$stoicReleaseDir/script"
  stoicHostCoreSyncDir = "$stoicReleaseDir/sync"

  stoicHostUsrConfigDir = System.getenv("STOIC_CONFIG").let {
    if (it.isNullOrBlank()) { "${System.getenv("HOME")}/.config/stoic" } else { it }
  }
  stoicHostUsrSyncDir = "$stoicHostUsrConfigDir/sync"
  stoicHostUsrPluginSrcDir = "$stoicHostUsrConfigDir/plugin"

  try {
    Entrypoint().main(rawArgs)
    exitProcess(0)
  } catch (e: PithyException) {
    // If we have a pithy message to display to the user, we'll display just that message
    // (unless debug logging is enabled) and then exit with status code 1.
    logDebug { e.stackTraceToString() }
    if (e.pithyMsg != null) {
      System.err.println(e.pithyMsg)
    } else {
      logDebug { "(no pithyMsg)" }
    }
    exitProcess(e.exitCode)
  } catch (e: Exception) {
    // We don't have a pithy message
    logError { e.stackTraceToString() }
    exitProcess(1)
  }
}

fun runTool(entrypoint: Entrypoint) {
  ToolCommand()
    .subcommands(
      ShellCommand(entrypoint),
      RsyncCommand(entrypoint),
      SetupCommand(entrypoint),
      ListCommand(entrypoint),
    ).main(entrypoint.subcommandArgs)
}

fun runPlugin(entrypoint: Entrypoint): Int {
  val pluginJar = resolvePluginModule(entrypoint)

  if (!entrypoint.restartApp) {
    // `adb forward`-powered fast path
    val serverSocketName = serverSocketName(entrypoint.pkg)
    val portStr = adbProcessBuilder(
      "forward", "tcp:0", "localabstract:$serverSocketName"
    ).stdout()
    try {
      Socket("localhost", portStr.toInt()).use {
        val pluginParsedArgs = PluginParsedArgs(
          pkg = entrypoint.pkg,
          restartApp = entrypoint.restartApp,
          startIfNeeded = !entrypoint.noStartIfNeeded,
          pluginModule = entrypoint.subcommand,
          pluginArgs = entrypoint.subcommandArgs,
          pluginEnvVars = entrypoint.env.toMap(),
        )
        val client = PluginClient(pluginJar, pluginParsedArgs, it.inputStream, it.outputStream)
        return client.handle()
      }
    } catch (e: PithyException) {
      // PithyException will be caught at the outermost level
      throw e
    } catch (e: Exception) {
      logInfo { "fast-path failed" }
      logDebug { e.stackTraceToString() }
      // Fall through to slow-path
    } finally {
      adbProcessBuilder("forward", "--remove", portStr)
    }
  }

  logInfo { "slow-path" }

  // slow-path
  syncDevice()

  // TODO: other stoic args
  var stoicArgs = mutableListOf("--log", minLogLevel.level.toString(), "--pkg", entrypoint.pkg)
  if (entrypoint.restartApp) {
    stoicArgs += listOf("--restart")
  }

  return adbProcessBuilder(
    "shell",
    shellEscapeCmd(listOf("$stoicDeviceSyncDir/bin/stoic") +
      stoicArgs +
      listOf(entrypoint.subcommand) +
      entrypoint.subcommandArgs)
  ).inheritIO().start().waitFor()
}

fun checkRequiredSdkPackages(vararg required: String) {
  runCommand(
    listOf("$stoicReleaseDir/script/check_required_sdk_packages.sh") + required,
    inheritIO = true
  )
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
  val exitCode = adbProcessBuilder("shell", exitCodeCmd)
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
      check(0 == adbProcessBuilder("push", "$stoicReleaseDir/sync/bin/rsync", "/data/local/tmp")
        .inheritIO()
        .redirectInput(devNullInput)
        .start()
        .waitFor())
      check(0 == adbProcessBuilder(
          "shell", """
            su shell mkdir -p $binDir 
            mv /data/local/tmp/rsync $binDir
            chown shell:shell $adbRsyncPath
          """.trimIndent())
          .inheritIO()
        .redirectInput(devNullInput)
        .start()
        .waitFor())
    } else {
      check(0 == adbProcessBuilder("shell", "mkdir -p $binDir")
        .inheritIO()
        .redirectInput(devNullInput)
        .start()
        .waitFor())
      check(0 == adbProcessBuilder("push", "$stoicReleaseDir/sync/bin/rsync", adbRsyncPath)
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
  runCommand(arsyncCmd, inheritIO = true, envOverrides = mapOf("ANDROID_SERIAL" to adbSerial))
}

fun syncDevice() {
  logInfo { "syncing device ..." }
  arsync("--archive", "--delete", "$stoicHostCoreSyncDir/", "$stoicHostUsrSyncDir/", "adb:$stoicDeviceSyncDir/")

  // We remove write permissions to stop people from accidentally writing to files that will be
  // subsequently overwritten by the next sync
  // For better latency we do this in the background
  adbProcessBuilder("shell", "chmod -R a-w $stoicDeviceSyncDir/")
    .start()
  logInfo { "... done syncing device" }
}

fun shellEscapeCmd(cmdArgs: List<String>): String {
  return if (cmdArgs.isEmpty()) {
    ""
  } else {
    return ProcessBuilder(listOf("bash", "-c", """ printf " %q" "$@" """, "stoic") + cmdArgs).stdout().drop(0)
  }
}

fun resolvePluginModule(entrypoint: Entrypoint): String? {
  val pluginModule = entrypoint.subcommand
  logDebug { "Attempting to resolve '$pluginModule'" }
  if (listOf(entrypoint.isDemo, entrypoint.isBuiltin, entrypoint.isUser).count { it } > 1) {
    throw PithyException("At most one of --demo/--builtin/--user may be specified")
  }

  val pluginDexJar = "$pluginModule.dex.jar"
  if (entrypoint.userAllowed) {
    val usrPluginSrcDir = "$stoicHostUsrPluginSrcDir/$pluginModule"
    if (File(usrPluginSrcDir).exists()) {
      logBlock(LogLevel.DEBUG, { "Building $usrPluginSrcDir/$pluginModule" }) {
        // TODO: In the future, we should allow building a simple jar and stoic handles packaging it
        // into a dex.jar, as needed
        ProcessBuilder("./gradlew", "--quiet", ":$pluginModule:dexJar")
          .inheritIO()
          .directory(File(stoicHostUsrPluginSrcDir))
          .waitFor(0)
        adbProcessBuilder("shell", "mkdir", "-p", stoicDeviceDevJarDir)

        return "$stoicHostUsrPluginSrcDir/$pluginModule/build/libs/$pluginDexJar"
      }
    }

    logDebug { "$usrPluginSrcDir does not exist - falling back to prebuilt locations." }

    val usrPluginDexJar = File("$stoicHostUsrSyncDir/plugins/$pluginDexJar")
    if (usrPluginDexJar.exists()) {
      return usrPluginDexJar.canonicalPath
    }
  }

  if (entrypoint.demoAllowed) {
    val corePluginDexJar = File("$stoicHostCoreSyncDir/plugins/$pluginDexJar")
    if (corePluginDexJar.exists()) {
      return corePluginDexJar.canonicalPath
    }
  }

  if (entrypoint.builtinAllowed) {
    return null
  }

  throw PithyException("plugin $pluginModule not found.")
}


private fun resolveAdbSerial(androidSerial: String?) {
  adbSerial = if (androidSerial != null) {
    androidSerial
  } else {
    val serialFromEnv = System.getenv("ANDROID_SERIAL")
    serialFromEnv
      ?: try {
        ProcessBuilder("adb", "get-serialno").stdout(0)
      } catch (e: FailedExecException) {
        if (e.errorOutput != null) {
          // This is probably just a message saying either
          //   "error: more than one device/emulator"
          // or
          //   "adb: no devices/emulators found"
          throw PithyException(e.errorOutput)
        } else {
          throw e
        }
      }
  }
}

fun adbProcessBuilder(vararg args: String): ProcessBuilder {
  val procArgs = listOf("adb", "-s", adbSerial) + args
  logDebug { "adbProcBuilder($procArgs)" }
  return ProcessBuilder(procArgs)
}
