package com.square.stoic.android.server

import android.util.Log
import com.square.stoic.StoicJvmti
import com.square.stoic.common.LogLevel
import com.square.stoic.common.minLogLevel

@Suppress("unused")
fun main(stoicDir: String) {
  Log.i("stoic", "start of AndroidServerJarKt.main")

  // RegisterNatives happened before we were called, so we mark Jvmti as ready to use
  StoicJvmti.markInitialized()

  minLogLevel = LogLevel.DEBUG

  ensureServer(stoicDir)
}