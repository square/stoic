package com.squareup.stoic.jvmti

import com.squareup.stoic.threadlocals.jvmti
import java.lang.reflect.Method

fun Method.forEach(block: (frame: StackFrame) -> Unit) {
  val methodId = VirtualMachine.nativeFromReflectedMethod(this)
  val jvmtiMethod = JvmtiMethod[methodId]
  val location = jvmtiMethod.startLocation
  jvmti.breakpoint(location) { frame -> block(frame) }
}

fun JvmtiMethod.forEach(block: (frame: StackFrame) -> Unit) {
  jvmti.breakpoint(startLocation) { frame -> block(frame) }
}
