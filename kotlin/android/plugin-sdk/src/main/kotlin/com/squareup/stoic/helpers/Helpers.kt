/**
 * This package provides safe replacements for operations that don't make sense inside of a Stoic
 * plugin. For example, if you call Kotlin's println, the output will go to logcat. The println here
 * will send its output to the plugin's stdout.
 */
package com.squareup.stoic.helpers

import com.squareup.stoic.threadlocals.stoic

fun println(x: Any?) = stoic.stdout.println(x)
fun print(x: Any?) = stoic.stdout.print(x)
fun eprintln(x: Any?) = stoic.stderr.println(x)
fun eprint(x: Any?) = stoic.stderr.print(x)

/**
 * Exit the plugin with the given exit code.
 */
fun exit(code: Int) = stoic.exitPlugin(code)
fun exitProcess(code: Int) = stoic.exitPlugin(code)

/**
 * Create a new thread, calling kotlin.concurrent.thread under the covers, but connecting the
 * stoic thread-local with that thread so any code run on it will be connected to the current
 * plugin
 */
fun thread(timeoutMs: Long? = null, runnable: Runnable) = stoic.thread(timeoutMs, runnable)
