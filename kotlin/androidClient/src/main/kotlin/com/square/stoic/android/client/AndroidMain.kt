package com.square.stoic.android.client

import com.square.stoic.common.LogLevel.WARN
import com.square.stoic.common.MainParsedArgs
import com.square.stoic.common.PithyException
import com.square.stoic.common.logDebug
import com.square.stoic.common.logError
import com.square.stoic.common.minLogLevel
import kotlin.system.exitProcess

val seLinuxViolationDetector = SELinuxViolationDetector()

fun main(args: Array<String>) {
  //System.err.println("start of AndroidMain.main")

  val exitCode = try {
    wrappedMain(args)
  } catch (e: PithyException) {
    // If we have a pithy message to display to the user, we'll display just that message
    // (unless debug logging is enabled) and then exit with status code 1.
    logDebug { e.stackTraceToString() }
    System.err.println(e.pithyMsg)
    seLinuxViolationDetector.showViolations()
    e.exitCode
  } catch (e: Exception) {
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

fun wrappedMain(args: Array<String>): Int {
  minLogLevel = WARN
  val mainParsedArgs = MainParsedArgs.parse(args)
  return when (mainParsedArgs.command) {
    "shell" -> throw PithyException("stoic shell from Android not yet supported")
    else -> AndroidPluginRunner(mainParsedArgs).run()
  }
}