package com.square.stoic.jvmti

import com.square.stoic.highlander

/**
 * Analogous to https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/com/sun/jdi/Method.html
 *
 * See https://docs.oracle.com/javase/8/docs/platform/jvmti/jvmti.html#method for JVMTI method
 * functions
 */
class Method(clazz: Class<*>?, val jmethodId: JMethodId, name: String?, signature: String?, generic: String?) {
  private val privateClazz = clazz
  private val privateName = name
  private val privateSignature = signature
  private val privateGeneric = generic

  val clazz: Class<*> get() {
    if (privateClazz != null) {
      return privateClazz
    } else {
      TODO("Look-up via GetMethodDeclaringClass")
    }
  }

  val name: String get() {
    if (privateName != null) { return privateName } else { TODO() }
  }

  val signature: String get() {
    if (privateSignature != null) { return privateSignature } else { TODO() }
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
    try {
      return variables.filter { it.slot >= maxLocals - argsSize }
    } catch (e: JvmtiException) {
      if (e.errorCode != JVMTI_ERROR_ABSENT_INFORMATION) {
        throw e
      }

      val method = VirtualMachine.nativeToReflectedMethod(clazz, jmethodId, false) as java.lang.reflect.Method
      var slot = maxLocals - argsSize
      val vars = mutableListOf<LocalVariable<*>>()
      for (param in method.parameterTypes) {
        val slotSize = when (param) {
          java.lang.Long.TYPE, java.lang.Double.TYPE -> 2
          else -> 1
        }
        vars.add(LocalVariable<Any>(0, 1, null, VirtualMachine.nativeGetClassSignature(param), null, slot))

        slot += slotSize
      }

      return vars
    }
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