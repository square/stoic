package com.square.stoic.android.server

interface StoicNamedPlugin {
  fun run(args: List<String>): Int
}