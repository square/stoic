#!/usr/bin/env stoic shebang --allowed-stoic-options pkg --
import com.squareup.stoic.print.*

fun main(args: List<String>): Int {
  println("args: $args")
  return 0
}
