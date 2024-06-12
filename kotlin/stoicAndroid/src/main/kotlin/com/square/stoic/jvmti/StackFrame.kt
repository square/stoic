package com.square.stoic.jvmti

/**
 * Analog of https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/com/sun/jdi/StackFrame.html
 */
class StackFrame(val thread: Thread, val height: Int, val location: Location) {
  val stackTrace get(): List<StackTraceElement> {
    return thread.stackTrace.takeLast(height)
  }

  fun <T> get(variable: LocalVariable<T>): T {
    val thread = Thread.currentThread()
    when (variable.signature) {
      "I", "Z", "B", "S" -> {
        val intRepr = VirtualMachine.nativeGetLocalInt(thread, height, variable.slot)
        return when (variable.signature) {
          "I" -> intRepr
          "Z" -> (intRepr != 0)
          "B" -> intRepr.toByte()
          "S" -> intRepr.toShort()
          else -> throw Throwable("Impossible")
        } as T
      }
      "J" -> return VirtualMachine.nativeGetLocalLong(thread, height, variable.slot) as T
      "F" -> return VirtualMachine.nativeGetLocalFloat(thread, height, variable.slot) as T
      "D" -> return VirtualMachine.nativeGetLocalDouble(thread, height, variable.slot) as T
      else -> {
        check(variable.signature.startsWith("L"))
        return VirtualMachine.nativeGetLocalObject(thread, height, variable.slot) as T
      }
    }
  }

  fun <T> set(variable: LocalVariable<T>, value: T) {
    TODO()
  }
}