import android.app.Application
import com.square.stoic.helpers.*
import com.square.stoic.threadlocals.jvmti

/**
 * Welcome to stoic! Please use this plugin as a scrapbook to explore what
 * stoic can do. You can run this plugin with
 *
 *   stoic scrapbook arg1 arg2 ...
 *
 * The main function is the entrypoint to your plugin. Any args you pass on the
 * command-line will appear as args here. Whatever you return (0-255) will be
 * the exit code.
 *
 * The `import com.square.stoic.print.*` imports println/eprintln functions
 * that are connected to the stdout/stderr of `stoic`.  (That's a fancy way of
 * saying that whenever you call println/eprintln it will not go to logcat but
 * instead appear as output of `stoic`). It also imports print/eprint versions
 * in case you don't want to print the newline.
 *
 * You can also import `com.square.stoic.stoic` and use `stoic.stdin` to access
 * stdin (TODO: this has not been adequately tested).
 *
 * If you need to run any code on other threads, please import
 * `com.square.stoic.stoic` and use the APIs there instead of using the
 * threading mechanisms directly. e.g.
 *
 * ```
 * stoic.thread {
 *   ...
 * }
 * ```
 *
 * See https://github.com/square/stoic/PLUGINS.md for more details on authoring
 * stoic plugins.
 */
fun main(args: Array<String>) {
  val instances = jvmti.instances(Application::class.java)
  if (instances.size != 1) {
    eprintln("Expected exactly one Application, but found: $instances")
    exit(1)
  }

  instances.forEach {
    println("android.app.Application: $it")
  }
}
