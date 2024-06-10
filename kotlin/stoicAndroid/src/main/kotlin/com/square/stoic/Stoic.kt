package com.square.stoic

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.square.stoic.jvmti.BreakpointRequest
import com.square.stoic.jvmti.EventCallback
import com.square.stoic.jvmti.EventRequest
import com.square.stoic.jvmti.VirtualMachine
import java.io.InputStream
import java.io.PrintStream
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

// internalStoic should only be used in callWith. stoic (as defined in ThreadLocals) should not be
// used
internal val internalStoic = ThreadLocal<Stoic>()

/**
 * A callback that will be invoked when a hook
 */
interface HookCallback {
  /**
   * To invoke the original, call runnable.run. If you don't invoke the original, it will be invoked
   * immediately after you return. TODO: Maybe we should use PopFrame to avoid this requirement?
   */
  fun onInvoke(runnable: Runnable)
}

class StoicJvmti private constructor() {
  fun <T> getInstances(clazz: Class<T>, includeSubclasses: Boolean = true): Array<T> {
    return VirtualMachine.nativeGetInstances(clazz, includeSubclasses)
  }

  fun breakpoint(clazz: Class<*>, methodName: String, methodSig: String, runnable: Runnable): BreakpointRequest {
    val method = VirtualMachine.concreteMethodByName(clazz, methodName, methodSig)
    val location = method.location()
    return VirtualMachine.eventRequestManager.createBreakpointRequest(location, object: EventCallback {
      override fun onEvent(events: Iterable<EventRequest>) {
        runnable.run()
      }
    })
  }

  val virtualMachine: VirtualMachine get() {
    return VirtualMachine
  }

  // TODO: maybe we should make unregisterCallback take the same callback parameter? Maybe
  // we should track a Set of callbacks?
  // TODO: we need to carefully account for all APIs we use, and disallow them from being hooked
  // perhaps we should return a Closeable and use close to unregister?
  external fun <T> registerCallback(clazz: Class<T>, name: String, sig: String, callback: HookCallback)
  external fun <T> unregisterCallback(clazz: Class<T>, name: String, sig: String)

  // Include other helpers with jvmti deps here

  companion object {
    @Volatile var privateIsInitialized: Boolean = false
    private val stoicJvmti: StoicJvmti = StoicJvmti()
    val isInitialized: Boolean get() = privateIsInitialized

    fun get() : StoicJvmti {
      // We'd like to allow for plugins that get run without jvmti, but they'll have to be aware that
      // it's unsafe for them to access jvmti
      assert(isInitialized)
      return stoicJvmti
    }

    // Call this if/when we registerNatives
    fun markInitialized() {
      privateIsInitialized = true
    }
  }
}

class Stoic(
  val env: Map<String, String>,
  val stdin: InputStream,
  val stdout: PrintStream,
  val stderr: PrintStream,
) {
  val jvmti: StoicJvmti
    get() = StoicJvmti.get()

  // This should be the only place in this file that uses internalStoic
  fun <T> callWith(forwardUncaught: Boolean = false, callable: Callable<T>): T {
    val oldStoic = internalStoic.get()
    internalStoic.set(this)
    try {
      return callable.call()
    } catch (t: Throwable) {
      if (forwardUncaught) {
        // TODO: This should become the default when I have it working
        stderr.println(
          "TODO: forward uncaught exception and bring down the plugin without killing the process"
        )
        stderr.println(t.stackTraceToString())
        throw t
      } else {
        stderr.println(t.stackTraceToString())
        throw t
      }
    } finally {
      internalStoic.set(oldStoic)
    }
  }

  fun <T> wrapCallable(callable: Callable<T>): Callable<T> {
    return Callable {
      callWith {
        callable.call()
      }
    }
  }

  fun wrapRunnable(runnable: Runnable): Runnable {
    return Runnable {
      callWith {
        runnable.run()
      }
    }
  }

  /**
   * runOnLooper/runOnExecutor/thread
   *
   * These provide mechanisms for running stoic plugin code on different threads. These should be
   * used instead of the raw looper/executor/thread APIs because they will handle forwarding the
   * stoic thread-local, and reporting uncaught exceptions to stderr.
   *
   * They also provide an optional timeoutMs that can be used to wait for the asynchronous operation
   * to complete.
   *
   * TODO: Run-delayed and Future variants
   *
   * If you are using a custom async mechanism you can provide support for it by using
   * using wrapRunnable (for non-blocking behavior) or LatchedRunnable (for blocking)
   */

  fun runOnLooper(looper: Looper, timeoutMs: Long? = null, runnable: Runnable) {
    if (timeoutMs != null) {
      val latchedRunnable = LatchedRunnable(this, runnable)
      Handler(looper).post(latchedRunnable)
      latchedRunnable.waitFor(timeoutMs)
    } else {
      Handler(looper).post(wrapRunnable(runnable))
    }
  }

  fun runOnExecutor(executor: Executor, timeoutMs: Long? = null, runnable: Runnable) {
    if (timeoutMs != null) {
      val latchedRunnable = LatchedRunnable(this, runnable)
      executor.execute(latchedRunnable)
      latchedRunnable.waitFor(timeoutMs)
    } else {
      executor.execute(wrapRunnable(runnable))
    }
  }

  fun thread(timeoutMs: Long? = null, runnable: Runnable): Thread {
    if (timeoutMs != null) {
      val latchedRunnable = LatchedRunnable(this, runnable)
      val t = rawStoicThread(latchedRunnable)
      latchedRunnable.waitFor(timeoutMs)
      return t // The thread will be done by this time
    } else {
      return kotlin.concurrent.thread {
        wrapRunnable(runnable).run()
      }
    }
  }

  private fun rawStoicThread(runnable: Runnable): Thread {
    return kotlin.concurrent.thread {
      callWith { runnable.run() }
    }
  }

  fun getenv(name: String): String? {
    return env[name] ?: System.getenv(name)
  }

  /**
   * The src path of the shebang script used to invoke stoic, or null if stoic wasn't invoked via a
   * shebang.
   */
  val shebangSrcPath: String? get() {
    return getenv("STOIC_SHEBANG_SRC_PATH")
  }

  // It'd be nice to allow plugins to use System.in/out/err directly, but it's easy to end up with
  // weird problems when you System.setOut/setErr so I abandoned this approach. I'd need to be
  // careful I didn't write to System.err anywhere. Here's a StackOverflowError I encountered
  // (it's worse because I catch Throwable and print the result to stderr so I wasn't even seeing
  // that). So I abandoned this approach.
  // TODO: Allow plugins to use System.in/out/err by rewriting their bytecode, AOP-style to use
  // thread-local stdin/stdout/sterr that get propagated by runWithStoic.
  //
  //  ...
  //	at java.io.PrintStream.write(PrintStream.java:503)
  //	at com.square.stoic.ThreadLocalOutputStream.write(Stoic.kt:141)
  //	at java.io.PrintStream.write(PrintStream.java:503)
  //	at com.square.stoic.ThreadLocalOutputStream.write(Stoic.kt:141)
  //	at java.io.PrintStream.write(PrintStream.java:503)
  // 	at com.square.stoic.ThreadLocalOutputStream.write(Stoic.kt:141)
  // 	at java.io.PrintStream.write(PrintStream.java:503)
  // 	at sun.nio.cs.StreamEncoder.writeBytes(StreamEncoder.java:221)
  // 	at sun.nio.cs.StreamEncoder.implWrite(StreamEncoder.java:282)
  // 	at sun.nio.cs.StreamEncoder.write(StreamEncoder.java:125)
  // 	at java.io.OutputStreamWriter.write(OutputStreamWriter.java:207)
  // 	at java.io.BufferedWriter.flushBuffer(BufferedWriter.java:129)
  // 	at java.io.PrintStream.write(PrintStream.java:553)
  // 	at java.io.PrintStream.print(PrintStream.java:698)
  // 	at java.io.PrintStream.println(PrintStream.java:835)
  // 	at com.square.stoic.common.StoicKt.log(Stoic.kt:23)
  // 	at com.square.stoic.common.StoicKt.logDebug(Stoic.kt:28)
  // 	at com.square.stoic.common.NamedPipeServer.accept(NamedPipeServer.kt:135)
  // 	at com.square.stoic.android.server.AndroidServerKt.main(AndroidServer.kt:51)
  //

  fun exitPlugin(code: Int) {
    throw ExitCodeException(code)
  }
}

/**
 * A runnable that waits for itself to run. This is used to run code "asynchronously" and wait for
 * it to complete.
 */
class LatchedRunnable(stoicInstance: Stoic, runnable: Runnable) : Runnable {
  private val wrappedRunnable = stoicInstance.wrapRunnable(runnable)
  private val startUptimeMillis = SystemClock.uptimeMillis()
  private val runnableStartUptimeMillisAtomic = AtomicLong(-1)
  private val latch = CountDownLatch(1)
  private val crash = AtomicReference<Throwable>()
  private val ranOnThread = AtomicReference<Thread>()

  override fun run() {
    runnableStartUptimeMillisAtomic.set(SystemClock.uptimeMillis())
    ranOnThread.set(Thread.currentThread())
    try {
      wrappedRunnable.run()
    } catch (t: Throwable) {
      crash.set(t)
    } finally {
      latch.countDown()
    }
  }

  fun waitFor(timeoutMs: Long) {
    if (!latch.await(timeoutMs, MILLISECONDS)) {
      val runnableStartUptimeMillis = runnableStartUptimeMillisAtomic.get()
      val msg = if (runnableStartUptimeMillis <= 0) {
        "Unable to schedule $wrappedRunnable within ${timeoutMs}ms"
      } else {
        val scheduleDelay = runnableStartUptimeMillis - startUptimeMillis
        "$wrappedRunnable (scheduled after ${scheduleDelay}ms) did not complete within ${timeoutMs}ms"
      }
      throw TimeoutException(msg).also { e ->
        ranOnThread.get()?.stackTrace?.also { e.stackTrace = it }
      }
    }
  }
}

// Could be useful to someone writing code that runs on either Android/JVM
val isAndroid = try {
  Build.VERSION.SDK_INT > -1
} catch (e: Throwable) {
  false
}