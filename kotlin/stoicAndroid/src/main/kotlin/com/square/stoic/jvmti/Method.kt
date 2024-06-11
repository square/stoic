package com.square.stoic.jvmti

import com.square.stoic.highlander

/**
 * Analogous to https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/com/sun/jdi/Method.html
 *
 * See https://docs.oracle.com/javase/8/docs/platform/jvmti/jvmti.html#method for JVMTI method
 * functions
 */
class Method(clazz: Class<*>?, val jmethodId: JMethodId) {
  private val privateClazz = clazz
  val clazz: Class<*> get() {
    if (privateClazz != null) {
      return privateClazz
    } else {
      TODO("Look-up via GetMethodDeclaringClass")
    }
  }

  /**
   * The start of the method
   */
  fun location(): Location {
    val jlocation = VirtualMachine.nativeGetMethodStartLocation(jmethodId)
    return Location(this, jlocation)
  }

  val arguments: List<LocalVariable<*>> get() {
    val argsSize = VirtualMachine.nativeGetArgumentsSize(jmethodId)
    val maxLocals = VirtualMachine.nativeGetMaxLocals(jmethodId)
    return variables.filter { it.slot >= maxLocals - argsSize }
  }

  val variables: List<LocalVariable<*>> get() =
    VirtualMachine.nativeGetLocalVariables(jmethodId).toList()

  fun variablesByName(name: String): List<LocalVariable<*>> {
    return variables.filter { it.name == name }
  }

  fun <T> argumentByName(name: String): LocalVariable<T> {
    return highlander(arguments.filter { it.name == name }) as LocalVariable<T>
  }
}