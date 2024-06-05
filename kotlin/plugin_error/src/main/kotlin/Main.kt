import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.square.stoic.helpers.*
import java.util.concurrent.CountDownLatch

/**
 * A Stoic plugin to inject errors into a running process
 */
@Suppress("unused")
fun main(args: Array<String>) {

  // TODO: support other error types
  injectAnr()
}

fun injectAnr() {

  // We acquire two locks from two different threads, opposite ordering, to cause a deadlock
  // This is the nicest type of ANR to debug.
  eprintln("Acquiring locks to hang the main thread...")
  val lock1 = Any()
  val latch1 = CountDownLatch(1)
  val lock2 = Any()
  val latch2 = CountDownLatch(1)
  HandlerThread("ANR Thread").also {
    it.start()
    Handler(it.looper).post {
      synchronized(lock1) {
        latch2.countDown()
        latch1.await()
        synchronized(lock2) {
          throw Exception("This shouldn't be possible")
        }
      }
    }
  }
  Handler(Looper.getMainLooper()).post {
    synchronized(lock2) {
      latch1.countDown()
      latch2.await()
      synchronized(lock1) {
        throw Exception("This shouldn't be possible")
      }
    }
  }

  eprintln("Main thread hung. Send input to trigger ANR detection.")

  // We can't run `monkey` from inside our process.
  // TODO: It'd be nice to have the ability to run preamble/postamble plugin code on the host
  // This would give us the ability trigger ANR detection automatically.
}
