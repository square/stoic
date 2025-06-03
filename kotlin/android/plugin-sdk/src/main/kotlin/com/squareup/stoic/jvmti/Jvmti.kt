package com.squareup.stoic.jvmti

import com.squareup.stoic.threadlocals.jvmti
import java.lang.reflect.Method
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.javaMethod

fun KFunction<*>.forEach(block: (frame: StackFrame) -> Unit) {
  this.javaMethod!!.forEach { frame -> block(frame) }
}

fun Method.forEach(block: (frame: StackFrame) -> Unit) {
  val methodId = VirtualMachine.nativeFromReflectedMethod(this)
  val jvmtiMethod = JvmtiMethod[methodId]
  val location = jvmtiMethod.startLocation
  jvmti.breakpoint(location) { frame -> block(frame) }
}

fun JvmtiMethod.forEach(block: (frame: StackFrame) -> Unit) {
  jvmti.breakpoint(startLocation) { frame -> block(frame) }
}
