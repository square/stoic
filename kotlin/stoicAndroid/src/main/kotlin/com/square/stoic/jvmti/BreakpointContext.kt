package com.square.stoic.jvmti

class BreakpointContext(val thread: Thread, val frameCount: Int) {
  // Gives non-deterministic results if the thread has completed the function where the breakpoint
  // was hit
  fun getStackTrace(): List<StackTraceElement> {
    return thread.stackTrace.takeLast(frameCount)
  }
}