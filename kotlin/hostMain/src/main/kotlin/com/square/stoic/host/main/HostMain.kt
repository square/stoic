package com.square.stoic.host.main

import com.square.stoic.common.FailedExecException
import com.square.stoic.common.LogLevel.WARN
import com.square.stoic.common.MainParsedArgs
import com.square.stoic.common.PithyException
import com.square.stoic.common.logDebug
import com.square.stoic.common.logError
import com.square.stoic.common.minLogLevel
import com.square.stoic.common.runCommand
import com.square.stoic.common.stdout
import com.square.stoic.common.waitFor
import java.io.File

import java.lang.ProcessBuilder.Redirect
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

  val home = System.getenv("HOME")
  stoicHostUsrConfigDir = "$home/.config/stoic"
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
    else -> HostPluginRunner(mainParsedArgs).run()
  }
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

  ProcessBuilder("rsync", "$stoicReleaseDir/jar/stoicAndroid.jar", "$stoicHostUsrPluginSrcDir/lib/")
    .inheritIO().waitFor(0)
  ProcessBuilder("rsync", "$stoicReleaseDir/jar/stoicAndroid-sources.jar", "$stoicHostUsrPluginSrcDir/lib/")
    .inheritIO().waitFor(0)

  println("""
    Setup complete!
    
    Next steps:
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
}

// stoic_dir="$(realpath "$(dirname "${BASH_SOURCE[0]}")"/..)"
// stoic_script_dir="$stoic_dir/script"
// stoic_kotlin_dir="$stoic_dir/kotlin"
// stoic_core_sync_dir="$stoic_dir/out/sync"
// stoic_device_dir=/data/local/tmp/stoic
// stoic_device_sync_dir="$stoic_device_dir/sync"
// stoic_usr_config_dir=~/.config/stoic
// stoic_usr_sync_dir="$stoic_usr_config_dir/sync"
// stoic_usr_plugin_src_dir="$stoic_usr_config_dir/src"
// stoic_host_wrapper_kt="$stoic_script_dir/HostWrapper.kt"
// stoic_target_api_level=34
// stoic_build_tools_version=34.0.0
// stoic_min_api_level=26
//
// source "$stoic_script_dir/android_serial.sh"
//
// arsync() {
//     # </dev/null so that adb shell doesn't steal our input stream
//     </dev/null adb shell mkdir -p "$stoic_device_sync_dir"
//
//     # Need rsync on device before we can rsync
//     local adb_rsync_path="$stoic_device_sync_dir/bin/rsync"
//
//     >/dev/null adb push --sync "$stoic_dir/prebuilt/rsync" "$adb_rsync_path"
//
//     # Note: I observe hangs with large files if I don't pass --blocking-io
//     rsync --blocking-io --rsh="sh $stoic_script_dir/arsync-wrapper.sh" "$@"
// }
//
// stoic_sync_device() {
//     _android_serial
//     arsync --archive --delete "$stoic_core_sync_dir/" "$stoic_usr_sync_dir/" adb:"$stoic_device_sync_dir/"
// }
//
// in_array() {
//     query="$1"
//     shift
//     #echo "query=$query"
//     #echo "elements=$*"
//     for element in "$@"; do
//         if [[ "$element" == "$query" ]]; then return 0; fi
//     done
//     return 1
// }
//
// run_escaped() {
//     cmd="$1"
//     shift
//
//     escaped_args=""
//     for arg in "$@"; do
//         escaped_args="$escaped_args $(printf '%q' "$arg")"
//     done
//
//     $cmd "$escaped_args"
// }
//
// # Bash script may be the wrong language here...
// stoic_shebang() {
//     # Step 1: parse out shebang-specific options
//     local host=0
//     local allow_stoic_options=()
//     local extra_options=()
//     while [ $# -gt 0 ]; do
//         local arg="$1"
//         shift
//
//         # Break the loop if the argument does not start with a dash
//         case "$arg" in
//           --)
//             local src_path
//             src_path="$(realpath "$1")"
//             extra_options+=("--env" "STOIC_SHEBANG_SRC_PATH=$src_path")
//             shift
//             # Remaining args are for the plugin
//             break
//             ;;
//           --allow-stoic-options)
//             IFS=',' read -r -a allow_stoic_options <<< "$1"
//             shift
//             ;;
//           --host)
//             host=1
//             ;;
//           --no-host)
//             host=0
//             ;;
//           *)
//             >&2 echo "Unexpected option: $arg"
//             exit 1
//             ;;
//         esac
//     done
//
//     # Step 2: Extract stoic options from plugin args (the remaining values in $@)
//     local new_plugin_args=()
//     while [ $# -gt 0 ]; do
//         arg="$1"
//         shift
//         case "$arg" in
//           --pkg)
//             # One-arg
//             if in_array "${arg:2}" "${allow_stoic_options[@]}"; then
//                 extra_options+=("$arg" "$1")
//                 shift
//             else
//                 new_plugin_args+=("$arg")
//             fi
//             ;;
//           --restart)
//             # Zero-arg
//             if in_array "${arg:2}" "${allow_stoic_options[@]}"; then
//                 extra_options+=("$arg")
//             else
//                 new_plugin_args+=("$arg")
//             fi
//             ;;
//           --host)
//             # Not passed onto device
//             if in_array "host" "${allow_stoic_options[@]}"; then
//                 host=1
//             else
//                 new_plugin_args+=("$arg")
//             fi
//             ;;
//           --no-host)
//             # Not passed onto device
//             if in_array "no-host" "${allow_stoic_options[@]}"; then
//                 host=0
//             else
//                 new_plugin_args+=("$arg")
//             fi
//             ;;
//           --*)
//             # One-arg
//             if in_array "${arg:2}" "${allow_stoic_options[@]}"; then
//                 >&2 echo "Unrecognized --allow-stoic_options: ${arg:2}"
//                 exit 1
//             else
//                 new_plugin_args+=("$arg")
//             fi
//             ;;
//           *)
//             new_plugin_args+=("$arg")
//             ;;
//         esac
//     done
//
//     # $@ = $new_plugin_args (If to avoid -u error)
//     if [ ${#new_plugin_args[@]} -gt 0 ]; then
//         set -- "${new_plugin_args[@]}"
//     fi
//
//     # Step 3: build and invoke the shebang
//
//     local shebang_jar_dir="$stoic_dir/out/shebang_jar"
//     local shebang_out_src_dir="$shebang_jar_dir/src$src_path"
//     mkdir -p "$shebang_out_src_dir"
//     local plugin_name
//     plugin_name="$(basename "$src_path")"
//
//     # We look for MainKt.main, so we need create one if the file is named
//     # something other than Main.kt
//     local srcs=("$src_path")
//     if [ "$plugin_name" != "Main.kt" ] && [ ! -e "$shebang_out_src_dir/Main.kt" ]; then
//         # Remove the '.kt' suffix and add 'Kt'
//         local shebangKt="${plugin_name%.kt}Kt"
//         >"$shebang_out_src_dir/Main.kt" cat <<- EOF
// 	import shebang.main as shebangMain  // Shebang script must be in package \`shebang\`
// 	fun main(args: List<String>): Int {
// 	  return shebangMain(args)
// 	}
// 	EOF
//         srcs+=("$shebang_out_src_dir/Main.kt")
//     fi
//
//     out_dex_jar_path="$shebang_out_src_dir/$plugin_name.dex.jar"
//     out_jvm_jar_path="$shebang_out_src_dir/$plugin_name-jvm.jar"
//
//     # Build a Makefile if we don't have one already
//     if [ ! -e "$shebang_out_src_dir/Makefile" ]; then
//         out_non_jvm_jar_path="$shebang_out_src_dir/$plugin_name.jar"
//
//         while IFS= read -r file; do
//             android_srcs+=("$file")
//         done < <(find "$stoic_kotlin_dir/android/src/main/kotlin" -type f)
//
//         # TODO: this Makefile doesn't handle file/dir names with spaces
//         >"$shebang_out_src_dir/Makefile" cat << EOF
// $out_dex_jar_path: ${srcs[*]} ${android_srcs[*]}
// 	kotlinc ${srcs[*]} ${android_srcs[*]} -classpath $ANDROID_HOME/platforms/android-$stoic_target_api_level/android.jar -d $out_non_jvm_jar_path
// 	$ANDROID_HOME/build-tools/34.0.0/d8 --min-api $stoic_min_api_level --output $out_dex_jar_path $out_non_jvm_jar_path
//
// $out_jvm_jar_path: ${srcs[*]} ${android_srcs[*]}
// 	kotlinc ${srcs[*]} ${android_srcs[*]} -classpath $ANDROID_HOME/platforms/android-$stoic_target_api_level/android.jar $stoic_host_wrapper_kt -include-runtime -d $out_jvm_jar_path
// EOF
//     fi
//
//     # Make the appropriate target
//     if [ "$host" -eq 1 ]; then
//         >/dev/null make -f "$shebang_out_src_dir/Makefile" "$out_jvm_jar_path"
//     else
//         >/dev/null make -f "$shebang_out_src_dir/Makefile" "$out_dex_jar_path"
//     fi
//
//     if [ "$host" -eq 1 ]; then
//         local plugin_env=()
//         while [ ${#extra_options[@]} -gt 0 ]; do
//             arg="${extra_options[0]}"
//             extra_options=("${extra_options[@]:1}")
//             case "$arg" in
//               --env)
//                 plugin_env+=("${extra_options[0]}")
//                 extra_options=("${extra_options[@]:1}")
//                 ;;
//               *)
//                 >&2 echo "Unrecognized option: $arg"
//                 exit 1
//                 ;;
//             esac
//         done
//
//         env "${plugin_env[@]}" java -classpath "$out_jvm_jar_path" HostWrapperKt "$@"
//     else
//         arsync "$out_dex_jar_path" adb:"$stoic_device_dir/shebang_jar/"
//         stoic_sync_device
//
//         # ${x[@]+"${x[@]}"}" idiom - see https://stackoverflow.com/a/61551944
//         #set -x
//         run_escaped "adb shell PATH=\$PATH:$stoic_device_sync_dir/bin" stoic --shebang "${extra_options[@]+"${extra_options[@]}"}" "$plugin_name" "$@"
//     fi
// }
