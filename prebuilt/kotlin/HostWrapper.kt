// This file exists because the JVM expects a main with a Unit return, but we
// use a main with an Int return. And the JVM passes args as an Array, but we
// use args as a List.

import kotlin.system.exitProcess
import com.square.stoic.Stoic

fun main(args: Array<String>) {
  val exitCode = Stoic(mapOf(), System.`in`, System.out, System.err).callWith {
    main(args.toList())
  }
  exitProcess(exitCode)
}
