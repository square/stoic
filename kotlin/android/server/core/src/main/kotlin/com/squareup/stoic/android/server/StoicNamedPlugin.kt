package com.squareup.stoic.android.server

interface StoicNamedPlugin {
  fun run(args: List<String>): Int
}