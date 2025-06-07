package com.squareup.stoic.host.main

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
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
import com.squareup.stoic.common.STOIC_PROTOCOL_VERSION
import com.squareup.stoic.common.logBlock
import com.squareup.stoic.common.logDebug
import com.squareup.stoic.common.logError
import com.squareup.stoic.common.logInfo
import com.squareup.stoic.common.minLogLevel
import com.squareup.stoic.common.runCommand
import com.squareup.stoic.common.serverSocketName
import com.squareup.stoic.common.stdout
import com.squareup.stoic.common.stoicDeviceSyncDir
import com.squareup.stoic.common.waitFor
import com.squareup.stoic.common.waitSocketName
import java.io.File
import java.io.FileFilter

import java.lang.ProcessBuilder.Redirect
import java.net.Socket
import java.nio.file.Paths
import kotlin.concurrent.thread
import kotlin.system.exitProcess


var isGraal: Boolean = false

// init-config populates th usr dirs
// TODO: we should publish our SDK jars to maven instead of copying them during init-config
lateinit var stoicHostUsrConfigDir: String
lateinit var stoicHostUsrSyncDir: String
lateinit var stoicHostUsrPluginSrcDir: String
lateinit var stoicHostUsrSdkDir: String

lateinit var stoicReleaseDir: String
lateinit var stoicHostScriptDir: String
lateinit var stoicHostCoreSyncDir: String
var androidSerial: String? = null

val adbSerial: String by lazy {
  androidSerial ?: run {
    val serialFromEnv = System.getenv("ANDROID_SERIAL")
    serialFromEnv
      ?: try {
        ProcessBuilder("adb", "get-serialno").stdout(0)
      } catch (e: FailedExecException) {
        e.errorOutput?.let {
          // This is probably just a message saying either
          //   "error: more than one device/emulator"
          // or
          //   "adb: no devices/emulators found"
          throw PithyException(it)
        } ?: run {
          throw e
        }
      }
  }
}


class Entrypoint : CliktCommand(
  name = "stoic",
) {
  companion object {
    const val DEFAULT_PACKAGE = "com.squareup.stoic.demoapp.withoutsdk"
  }
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

      Special functionality is available via `stoic --tool <tool-name> <tool-args>` - for details,
      see `stoic --list --tool`
    """.trimIndent()
  }

  // Track which options were explicitly set
  private val specifiedOptions = mutableSetOf<String>()

  fun verifyOptions(subcommand: String, allowedOptions: List<String>) {
    specifiedOptions.forEach {
      if (it !in allowedOptions) {
        throw UsageError("$it not allowed with $subcommand")
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

  val androidSerialArg by option(
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
  ).trackableOption().default(DEFAULT_PACKAGE)

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
  val isTool by option(
    "--tool",
    "-t",
    help = "run a tool - see `stoic --tool --list` for details"
  ).flag()
  val isList by option(
    "--list",
    "-l",
    help = "list plugins (pass --tool to list tools)"
  ).flag()

  val subcommand by argument(name = "plugin").optional()
  val subcommandArgs by argument(name = "plugin-args").multiple()

  var demoAllowed = false
  var builtinAllowed = false
  var userAllowed = false

  fun resolveAllowed() {
    val count = listOf(isDemo, isBuiltin, isUser).count { it }
    if (count == 0) {
      demoAllowed = true
      builtinAllowed = true
      userAllowed = true
    } else if (count > 1) {
      throw UsageError("--demo/--builtin/--user are mutually exclusive")
    } else if (isTool) {
      throw UsageError("--tool is invalid with --demo/--builtin/--user")
    } else if (isDemo) {
      demoAllowed = true
    } else if (isBuiltin) {
      builtinAllowed = true
    } else if (isUser) {
      userAllowed = true
    }
  }

  override fun run() {
    resolveAllowed()

    // We need to store this globally since we use it to resolve which device we connect to
    androidSerial = androidSerialArg

    if (restartApp && noStartIfNeeded) {
      throw CliktError("--restart-app and --no-start-if-needed are mutually exclusive")
    }

    if (listOf(verbose, debug, info).count { it } > 1) {
      throw CliktError("--verbose and --debug are mutually exclusive")
    }

    minLogLevel = if (verbose) {
      LogLevel.VERBOSE
    } else if (debug) {
      LogLevel.DEBUG
    } else if (info) {
      LogLevel.INFO
    } else {
      LogLevel.WARN
    }

    logDebug { "isGraal=$isGraal" }

    val exitCode = runPluginOrTool(this)
    if (exitCode != 0) {
      throw PithyException(null, exitCode)
    }
  }
}

class ShellCommand(val entrypoint: Entrypoint) : CliktCommand(name = "stoic shell") {
  init { context { allowInterspersedArgs = false } }
  override fun help(context: Context): String {
    return """
      like `adb shell` but syncs directories and initializes the shell env
 
      If it exists, $stoicHostCoreSyncDir will be synced to $stoicDeviceSyncDir
      If it exists, $stoicHostUsrConfigDir/shell.sh will be run to start the shell. You may
      reference the following environment variables in your shell.sh:
 
        STOIC_DEVICE_SYNC_DIR (this will be set to $stoicDeviceSyncDir)
        STOIC_TTY_OPTION (this will be set to one of -t/-tt/-T depending on the invocation of
        `stoic shell`)

    """.trimIndent()
  }

  val shellArgs by argument().multiple()

  val tty by option("--tty", "-t").flag()
  val forceTty by option("--force-tty", "-tt").flag()
  val disableTty by option("--disable-tty", "-T").flag()
  val noSync by option("--no-sync", "-n").flag()
  val defaultConfig by option("--default-config", "-d").flag()

  override fun run() {
    entrypoint.verifyOptions("shell", listOf("--verbose", "--debug", "--android-serial"))

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

    if (!noSync) {
      syncDevice()
    }

    val usrShellSh = "$stoicHostUsrConfigDir/shell.sh" // might not exist
    val prebuiltShellSh = "$stoicReleaseDir/template/usr_config/shell.sh"
    val shellSh = if (!defaultConfig && File(usrShellSh).exists()) {
      usrShellSh
    } else {
      prebuiltShellSh
    }

    try {
      runCommand(
        listOf("sh", shellSh) + shellArgs,
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

      `stoic rsync` is powered by actual rsync, so it supports the same options. Run
      `rsync --help` to see details.

      e.g. to sync a hypothetical docs directory from your laptop to your Android device:
      `stoic rsync --archive docs/ adb:/data/local/tmp/docs/`

      e.g. to sync a hypothetical docs directory from your Android device to your laptop:
      `stoic rsync --archive adb:/data/local/tmp/docs/ docs/`
    """.trimIndent()
  }

  val rsyncArgs by argument().multiple()

  override fun run() {
    entrypoint.verifyOptions("rsync", listOf("--verbose", "--debug", "--android-serial"))

    arsync(rsyncArgs)
  }
}

class InitConfigCommand(val entrypoint: Entrypoint) : CliktCommand(name = "stoic init-config") {
  override fun help(context: Context): String {
    return """
      initializes ~/.config/stoic
    """.trimIndent()
  }

  override fun run() {
    entrypoint.verifyOptions("init-config", listOf("--verbose", "--debug"))

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

    ProcessBuilder("rsync", "$stoicReleaseDir/jar/stoic-android-plugin-sdk.jar", "$stoicHostUsrSdkDir/")
      .inheritIO().waitFor(0)
    ProcessBuilder("rsync", "$stoicReleaseDir/jar/stoic-android-plugin-sdk-sources.jar", "$stoicHostUsrSdkDir/")
      .inheritIO().waitFor(0)

    newPlugin("scratch", ignoreExisting = true)

    println("""
      User config initialized!
      
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
  }
}

class NewPluginCommand(val entrypoint: Entrypoint) : CliktCommand(name = "stoic new-plugin") {
  override fun help(context: Context): String {
    return """
      Create a new plugin
    """.trimIndent()
  }

  val pluginName by argument("name")

  override fun run() {
    entrypoint.verifyOptions("new-plugin", listOf("--verbose", "--debug"))

    val usrPluginSrcDir = newPlugin(pluginName)

    System.err.println("New plugin src written to $usrPluginSrcDir")
  }
}

fun newPlugin(pluginName: String, ignoreExisting: Boolean = false): File {
  val pluginNameRegex = Regex("^[A-Za-z0-9_-]+$")
  if (!pluginName.matches(pluginNameRegex)) {
    throw PithyException("Plugin name must adhere to regex: ${pluginNameRegex.pattern}")
  }

  // Ensure the parent directory has been created
  File(stoicHostUsrPluginSrcDir).mkdirs()

  val usrPluginSrcDir = File("$stoicHostUsrPluginSrcDir/$pluginName")
  val scratchSrcDir = File("$stoicReleaseDir/template/scratch")

  if (usrPluginSrcDir.exists() && ignoreExisting) {
    return usrPluginSrcDir
  }

  // Copy the scratch template plugin
  ProcessBuilder(
    "cp",
    "-iR",
    scratchSrcDir.absolutePath,
    usrPluginSrcDir.absolutePath
  ).inheritIO().waitFor(0)

  File(usrPluginSrcDir, ".stoic_template_version").writeText(StoicProperties.STOIC_VERSION_NAME)

  // Replace "scratch" with the name of the plugin
  check(ProcessBuilder.startPipeline(
    listOf(
      ProcessBuilder("grep", "--recursive", "--files-with-matches", "--null", "scratch", ".")
        .directory(usrPluginSrcDir)
        .redirectError(Redirect.INHERIT),
      ProcessBuilder("xargs", "-0", "sed", "-i", "", "s/scratch/$pluginName/g")
        .directory(usrPluginSrcDir)
        .inheritIO()
        .redirectInput(Redirect.PIPE)
    )
  ).all { it.waitFor() == 0 })

  return usrPluginSrcDir
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
  stoicHostUsrSdkDir = "$stoicHostUsrConfigDir/sdk"

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

fun runList(entrypoint: Entrypoint): Int {
  entrypoint.verifyOptions("--list", listOf())
  if (entrypoint.subcommand != null) {
    throw UsageError("`stoic --list` doesn't take positional arguments")
  } else if (entrypoint.isTool) {
    // TODO: deduplicate this list with the one in runTool
    listOf("shell", "rsync", "init-config", "new-plugin").forEach {
      println(it)
    }
  } else {
    val pluginList = gatherPluginList(entrypoint)
    pluginList.forEach {
      println(it)
    }
  }

  return 0
}

fun gatherPluginList(entrypoint: Entrypoint): List<String> {
  // TODO: Invoke builtin stoic-list to get list of builtin plugins (and allow --package)
  val pluginList = mutableListOf<String>()

  val dexJarFilter = object : FileFilter {
    override fun accept(file: File): Boolean {
      return file.isFile && file.name.endsWith(".dex.jar")
    }
  }

  entrypoint.resolveAllowed()

  if (entrypoint.userAllowed) {
    val usrSourceDirs = File(stoicHostUsrPluginSrcDir).listFiles()!!
    usrSourceDirs.forEach {
      if (!it.name.startsWith(".")) {
        pluginList.add("${it.name} (--user)")
      }
    }

    val usrPrebuilts = File("$stoicHostUsrSyncDir/plugins").listFiles(dexJarFilter)!!
    usrPrebuilts.forEach {
      pluginList.add("${it.name.removeSuffix(".dex.jar")} (--user)")
    }
  }

  if (entrypoint.demoAllowed) {
    val demoPrebuilts = File("$stoicHostCoreSyncDir/plugins").listFiles(dexJarFilter)!!
    demoPrebuilts.forEach {
      pluginList.add("${it.name.removeSuffix(".dex.jar")} (--demo)")
    }
  }

  return pluginList
}

fun runTool(entrypoint: Entrypoint): Int {
  val toolName = entrypoint.subcommand
  val command = when (toolName) {
    "shell" -> ShellCommand(entrypoint)
    "rsync" -> RsyncCommand(entrypoint)
    "init-config" -> InitConfigCommand(entrypoint)
    "new-plugin" -> NewPluginCommand(entrypoint)
    "help" -> {
      entrypoint.echoFormattedHelp()
      return 0
    }
    "version" -> {
      entrypoint.echo("stoic version ${StoicProperties.STOIC_VERSION_NAME}")
      return 0
    }
    else -> {
      if (entrypoint.isTool) {
        // The user was definitely trying to run a tool
        throw PithyException("tool `$toolName` not found, to see list: stoic --tool --list")
      } else {
        // Maybe the user was trying to run a plugin
        throw PithyException("""
          plugin or tool `$toolName` not found, to see list:
          stoic --tool --list (for tools)
          stoic --list (for plugins)
          """.trimIndent())
      }
    }
  }

  command.main(entrypoint.subcommandArgs)
  return 0
}

fun runPluginFastPath(entrypoint: Entrypoint, dexJarInfo: Pair<File, String>?): Int {
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
        pluginModule = entrypoint.subcommand!!,
        pluginArgs = entrypoint.subcommandArgs,
        pluginEnvVars = entrypoint.env.toMap(),
      )
      val client = PluginClient(dexJarInfo, pluginParsedArgs, it.inputStream, it.outputStream)
      return client.handle()
    }
  } finally {
    adbProcessBuilder("forward", "--remove", portStr)
  }
}

fun runPluginOrTool(entrypoint: Entrypoint): Int {
  if (entrypoint.isList) {
    return runList(entrypoint)
  } else if (entrypoint.isTool) {
    return runTool(entrypoint)
  }

  // If resolvePluginModule returns null then we'll try assuming its a builtin (if we resolved the
  // device) if that fails, then we'll check for a tool
  val dexJarInfo = resolveUserOrDemo(entrypoint)

  val isPlugin = if (dexJarInfo != null || entrypoint.isBuiltin) {
    true
  } else if (entrypoint.pkg != Entrypoint.DEFAULT_PACKAGE) {
    // Tools don't take pkg as argument, so it must be a plugin
    true
  } else if (entrypoint.commandName in listOf("stoic-list", "stoic-status", "stoic-noop")) {
    // We know the list of plugins that the default package supports - if the command is on that
    // list, then it's a plugin
    // TODO: validate this list is sync'd with the actual supported plugins
    true
  } else {
    // Otherwise it's not a plugin
    false
  }

  return if (isPlugin) {
    runPlugin(entrypoint, dexJarInfo)
  } else {
    runTool(entrypoint)
  }
}

fun runPlugin(entrypoint: Entrypoint, dexJarInfo: Pair<File, String>?): Int {
  if (!entrypoint.restartApp) {
    try {
      return runPluginFastPath(entrypoint, dexJarInfo)
    } catch (e: PithyException) {
      // PithyException will be caught at the outermost level
      throw e
    } catch (e: Exception) {
      logInfo { "fast-path failed" }
      logDebug { e.stackTraceToString() }
    }
  }

  // force start the server, and then retry
  logInfo { "starting server via slow-path" }

  // TODO: this syncDevice is not usually necessary, and it adds 300-400ms
  //   it'd be better to pass some hash to stoic-attach and have it do an up-to-date check
  syncDevice()

  val startOption = if (entrypoint.restartApp) {
    "restart"
  } else if (entrypoint.noStartIfNeeded) {
    "do_not_start"
  } else {
    "start_if_needed"
  }

  val debugOption = if (minLogLevel <= LogLevel.DEBUG) {
    "debug_true"
  } else {
    "debug_false"
  }

  val proc = adbProcessBuilder(
    "shell",
    shellEscapeCmd(
      listOf(
        "$stoicDeviceSyncDir/bin/stoic-attach",
        "$STOIC_PROTOCOL_VERSION",
        entrypoint.pkg,
        waitSocketName(entrypoint.pkg),
        startOption,
        debugOption
      )
    )
  )
    .redirectInput(File("/dev/null"))
    .redirectOutput(Redirect.PIPE)
    .redirectErrorStream(true)
    .start()

  if (minLogLevel <= LogLevel.DEBUG) {
    proc.inputReader().use { inputReader ->
      thread {
        inputReader.lineSequence().forEach {
          logDebug { it }
        }
        if (proc.waitFor() != 0) {
          logError { "stoic-attach failed - see output above" }
        }
      }.join()
    }
  } else {
    if (proc.waitFor() != 0) {
      val stoicAttachOutput = proc.inputReader().readText()
      logError { "stoic-attach failed\n$stoicAttachOutput" }
    }
  }

  logInfo { "retrying fast-path" }
  return runPluginFastPath(entrypoint, dexJarInfo)
}

fun checkRequiredSdkPackages(vararg required: String) {
  runCommand(
    listOf("$stoicReleaseDir/script/check_required_sdk_packages.sh") + required,
    inheritIO = true
  )
}

// This version of arsync never pushes as root - TODO: allow this as an option
fun arsync(args: List<String>) {
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

  val brewRsync = if (ProcessBuilder("which", "brew").waitFor(null) == 0) {
    val brewPrefix = ProcessBuilder("brew", "--prefix").stdout()
    val brewRsync = "$brewPrefix/bin/rsync"

    // Force rsync to use the homebrew version, even if the system version is earlier in the PATH
    // This is useful because Mac ships an ancient version of rsync
    if (File(brewRsync).exists()) brewRsync else null
  } else {
    null
  }
  val rsyncPath = brewRsync ?: ProcessBuilder("which", "rsync").stdout()

  // I observe hangs with large files if I don't pass --blocking-io
  val arsyncCmd = listOf(rsyncPath, "--blocking-io", "--rsh=sh $wrapper") + args
  logDebug { "$arsyncCmd" }
  try {
    runCommand(arsyncCmd, inheritIO = true, envOverrides = mapOf("ANDROID_SERIAL" to adbSerial))
  } catch (e: FailedExecException) {
    logError { "$rsyncPath failed - check: $rsyncPath --version (Stoic needs 3.x.x)" }
    throw e
  }
}

fun syncDevice() {
  logInfo { "syncing device ..." }
  val opts = listOf("--archive", "--delete")

  // This won't necessarily exist - need to run `stoic init-config`
  val maybeUsrSyncDir = if (File(stoicHostUsrSyncDir).exists()) {
    listOf("$stoicHostUsrSyncDir/")
  } else {
    listOf()
  }

  arsync(
    opts + listOf("$stoicHostCoreSyncDir/") + maybeUsrSyncDir + listOf("adb:$stoicDeviceSyncDir/")
  )

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

fun resolveUserOrDemo(entrypoint: Entrypoint): Pair<File, String>? {
  val pluginName = entrypoint.subcommand!!
  logDebug { "Attempting to resolve '$pluginName'" }
  if (listOf(entrypoint.isDemo, entrypoint.isBuiltin, entrypoint.isUser).count { it } > 1) {
    throw PithyException("At most one of --demo/--builtin/--user may be specified")
  }

  if (pluginName.endsWith(".jar")) {
    if (!entrypoint.userAllowed) {
      throw PithyException("jar plugin are considered user - --demo/--builtin options are incompatible")
    }

    val file = File(pluginName)
    if (!file.exists()) {
      throw PithyException("File not found: $pluginName")
    }

    return DexJarCache.resolve(file)
  }

  val pluginDexJar = "$pluginName.dex.jar"
  if (entrypoint.userAllowed) {
    val usrPluginSrcDir = "$stoicHostUsrPluginSrcDir/$pluginName"
    if (File(usrPluginSrcDir).exists()) {
      val jarPath = logBlock(LogLevel.INFO, { "building $usrPluginSrcDir" }) {
        logInfo { "building plugin" }
        val prefix = "STOIC_BUILD_PLUGIN_JAR_OUT="
        try {
          ProcessBuilder("./build-plugin")
            .inheritIO()
            .directory(File(usrPluginSrcDir))
            .stdout()
            .lineSequence()
            .first { it.startsWith(prefix) }
            .removePrefix(prefix)
        } catch (e: NoSuchElementException) {
          logDebug { e.stackTraceToString() }
          throw PithyException("build-plugin must output line: $prefix<path-to-output-jar>")
        }
      }

      return DexJarCache.resolve(File(jarPath))
    }

    logDebug { "$usrPluginSrcDir does not exist - falling back to prebuilt locations." }

    val usrPluginDexJar = File("$stoicHostUsrSyncDir/plugins/$pluginDexJar")
    if (usrPluginDexJar.exists()) {
      return DexJarCache.resolve(usrPluginDexJar)
    } else if (entrypoint.isUser) {
      throw PithyException("User plugin `$pluginName` not found, to see list: stoic --list --user")
    }
  }

  if (entrypoint.demoAllowed) {
    val corePluginDexJar = File("$stoicHostCoreSyncDir/plugins/$pluginDexJar")
    if (corePluginDexJar.exists()) {
      return DexJarCache.resolve(corePluginDexJar)
    } else if (entrypoint.isDemo) {
      throw PithyException("Demo plugin `$pluginName` not found, to see list: stoic --list --demo")
    }
  }

  return null
}

fun adbProcessBuilder(vararg args: String): ProcessBuilder {
  val procArgs = listOf("adb", "-s", adbSerial) + args
  logDebug { "adbProcBuilder($procArgs)" }
  return ProcessBuilder(procArgs)
}
