package com.squareup.stoic.common

import com.squareup.stoic.common.LogLevel.VERBOSE
import java.io.File
import java.lang.ProcessBuilder.Redirect
import kotlin.math.exp

var minLogLevel: LogLevel = LogLevel.DEBUG

enum class LogLevel(val level: Int) {
  VERBOSE(2),
  DEBUG(3),
  INFO(4),
  WARN(5),
  ERROR(6),
  ASSERT(7);

  fun meetsMinimumLevel(minimumLevel: LogLevel): Boolean {
    return this.level >= minimumLevel.level
  }

  fun isLoggable(): Boolean {
    return this >= minLogLevel
  }
}

fun log(level: LogLevel, msg: () -> String) {
  if (level >= minLogLevel) {
    System.err.println(msg())
  }
}

fun logVerbose(msg: () -> String) = log(LogLevel.VERBOSE, msg)
fun logDebug(msg: () -> String) = log(LogLevel.DEBUG, msg)
fun logInfo(msg: () -> String) = log(LogLevel.INFO, msg)
fun logWarn(msg: () -> String) = log(LogLevel.WARN, msg)
fun logError(msg: () -> String) = log(LogLevel.ERROR, msg)

inline fun <T> logBlock(level: LogLevel, msg: () -> String, block: () -> T): T {
  val msgValue = if (level >= minLogLevel) {
    msg()
  } else {
    ""
  }

  log(level) { "Starting $msgValue..." }
  return runCatching { block() }
    .onSuccess { log(level) { "Finished $msgValue." } }
    .onFailure { e ->
      log(level) { "Aborted $msgValue (--verbose to see Throwable)" }
      log(VERBOSE) { e.stackTraceToString() }
    }
    .getOrThrow()
}

// JVM doesn't provide any way to set environment variables natively. Instead, we keep track of
// variables here, and set them in runCommand.
val globalEnvOverrides = mutableMapOf<String, String>()

fun runCommand(
  commandParts: List<String>,
  inheritIO: Boolean = false,
  shell: Boolean = false,
  directory: String? = null,
  chomp: Boolean = true,
  envOverrides: Map<String, String>? = null,
  expectedExitCode: Int = 0,
  redirectErrorStream: Boolean = false,
  redirectError: Redirect? = null,
  redirectOutputAndError: Redirect? = null,
  redirectOutput: Redirect? = null,
): String {
  logBlock(LogLevel.DEBUG, { "runCommand(${commandParts.toKotlinRepr()}, $inheritIO, $shell, $directory, $chomp, $envOverrides, $expectedExitCode)" }) {
    val builder = resolvedProcessBuilder(
      commandParts,
      shell = shell,
      directory = directory,
      envOverrides = envOverrides,
      redirectErrorStream = redirectErrorStream,
      redirectError = redirectError,
      redirectOutput = redirectOutput,
      redirectOutputAndError = redirectOutputAndError)

    if (inheritIO) {
      builder.inheritIO()
    }

    val process = builder
      .start()
      .also { it.waitFor() }

    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    if (exitCode != expectedExitCode) {
      val errorOutput = process.errorStream.bufferedReader()
        .readText()
      throw FailedExecException(
        exitCode,
        "Failed command: '${commandParts.toKotlinRepr()}', exitCode=$exitCode, error output:\n---\n$errorOutput---",
        errorOutput)
    }

    return if (chomp) {
      // We remove the trailing newline, if it exists - similar to Perl's chomp. This is handy since
      // we often want to get a simple output string with no newline, but most commands always output a
      // newline at the end
      output.removeSuffix("\n")
    } else {
      output
    }
  }
}

fun resolvedProcessBuilder(
    command: List<String>,
    shell: Boolean = false,
    directory: String? = null,
    envOverrides: Map<String, String>? = null,
    redirectErrorStream: Boolean = false,
    redirectError: Redirect? = null,
    redirectOutput: Redirect? = null,
    redirectOutputAndError: Redirect? = null,
  ): ProcessBuilder {
  val resolvedCommand = if (shell) {
    // The first item is a shell script to be evaluated by bash. The other items are parameters to
    // that shell script. (These parameters are zero-indexed)
    listOf("sh", "-c") + command
  } else {
    // Run the command through bash so that PATH is used to resolve the executable
    listOf("sh", "-c", "\"\$0\" \"\$@\"") + command
  }

  val builder = ProcessBuilder(resolvedCommand).redirectErrorStream(redirectErrorStream)
  if (redirectOutputAndError != null) {
    builder.redirectOutput(redirectOutputAndError).redirectError(redirectOutputAndError)
  } else if (redirectError != null) {
    builder.redirectError(redirectError)
  } else {
    builder.redirectError(Redirect.INHERIT)
  }
  if (redirectOutputAndError == null && redirectOutput != null) {
    builder.redirectOutput(redirectOutput)
  }

  val env = builder.environment()
  env.putAll(globalEnvOverrides)
  envOverrides?.let { env.putAll(envOverrides) }

  // This env var causes kotlinc/kotlinc-jvm to resolve to kotlinc-native
  //env.remove("KOTLIN_RUNNER")

  if (directory != null) {
    builder.directory(File(directory))
  }

  return builder
}

fun ProcessBuilder.waitFor(expectedExitCode: Int? = 0) {
  start().waitFor(expectedExitCode, command())
}

fun Process.waitFor(expectedExitCode: Int?, command: List<String>? = null) {
  val exitCode = waitFor()

  if (expectedExitCode != null && expectedExitCode != exitCode) {
    val errorOutput = errorStream?.reader()?.readText()?.let {
      if (it.isBlank()) { null } else { it.removeSuffix("\n") }
    }
    val errorOutputMsg = if (errorOutput != null) {
      ", error output:\n---\n$errorOutput\n---"
    } else {
      ""
    }
    val commandString = if (command != null) { "'${command.toKotlinRepr()}', " } else { "" }
    throw FailedExecException(
      exitCode,
      "Failed command: ${commandString}exitCode=$exitCode$errorOutputMsg",
      errorOutput)
  }
}

fun ProcessBuilder.stdout(expectedExitCode: Int? = 0, chomp: Boolean = true): String {
  redirectOutput(Redirect.PIPE)
  val process = start()
  process.waitFor(expectedExitCode, command())

  val output = process.inputStream.bufferedReader().readText()

  return if (chomp) {
    // We remove the trailing newline, if it exists - similar to Perl's chomp. This is handy since
    // we often want to get a simple output string with no newline, but most commands always output a
    // newline at the end
    output.removeSuffix("\n")
  } else {
    output
  }
}

fun Any?.toKotlinRepr(): String = when (this) {
    null -> "null"
    is CharSequence -> {
        val unquoted = this.toString()
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
            .replace("\b", "\\b")
            .replace("\r", "\\r")
            .replace("\"", "\\\"")
            .replace("\$", "\\\$")
        "\"" + unquoted + "\""
    }
    is List<*> -> this.toKotlinListRepr()  // Separate function for lists
    else -> this.toString()
}

fun List<*>.toKotlinListRepr(): String {
  val reprElements = this.joinToString(separator = ", ") { it.toKotlinRepr() }
  return "listOf($reprElements)"
}

const val SOCKET_PREFIX = "/stoic"
const val SERVER_SUFFIX = "server"
const val WAIT_SUFFIX = "wait"

// Socket name (in abstract namespace) for clients to connect to the server
fun serverSocketName(pkg: String): String {
  return "$SOCKET_PREFIX/$pkg/$SERVER_SUFFIX"
}

// Socket name (in abstract namespace) for server to connect to client (to tell it that its up)
fun waitSocketName(pkg: String): String {
  return "$SOCKET_PREFIX/$pkg/$WAIT_SUFFIX"
}
