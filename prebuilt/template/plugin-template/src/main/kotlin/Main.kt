// This is a template for your new plugin. Change it however you'd like.

// You may import whatever Android APIs you need
import android.os.Process.myPid
import android.os.Process.myTid

// Import helpers.* to provide access to alternate, plugin-friendly API
// implementations. For example, println normally prints to logcat, but
// helpers.println will tunnel through to Stoic's stdout.
import com.squareup.stoic.helpers.*

// Multiple instances of stoic may be active simultaneously - access the one
// that started your plugin via this thread-local
import com.squareup.stoic.threadlocals.stoic

fun main(args: Array<String>) {
  // You may access the arguments passed on the command-line
  println("main(${args.toList()})")

  // Stoic plugins run on their own background thread initially.
  println("Plugin running in process PID=${myPid()} on thread TID=${myTid()}")

  // Use runOnMainLooper to run code on the main thread. This will block the
  // plugin thread until it returns, and take care of updating thread-locals
  // such that println will tunnel through to the correct Stoic instance.
  // (use runOnLooper/runOnExecutor/runOnThread for running on other threads)
  runOnMainLooper {
    println("Plugin running on main thread (PID=${myPid()}, TID=${myTid()})")
  }

  // If you wish to exit with an error-code other than zero, you can call
  // exitPlugin explicitly.
  //exitPlugin(1)
}
