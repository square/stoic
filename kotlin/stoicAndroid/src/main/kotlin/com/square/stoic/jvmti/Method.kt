package com.square.stoic.jvmti

import com.square.stoic.LruCache
import com.square.stoic.highlander

/**
 * Analogous to https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/com/sun/jdi/Method.html
 *
 * See https://docs.oracle.com/javase/8/docs/platform/jvmti/jvmti.html#method for JVMTI method
 * functions
 */
class Method private constructor(val methodId: JMethodId) {
  init { check(methodId != 0L) }

  // These are fetched by nativeGetMethodMetadata
  private var privateClazz: Class<*>? = null
  private var privateName: String? = null
  private var privateSignature: String? = null
  private var privateGeneric: String? = null
  private var privateStartLocation: JLocation = Long.MIN_VALUE
  private var privateEndLocation: JLocation = Long.MIN_VALUE
  private var privateArgsSize = Int.MIN_VALUE
  private var privateMaxLocals = Int.MIN_VALUE

  // This is fetched by nativeGetLocalVariables(jmethodId).toList()
  private var privateVariables: List<LocalVariable<*>>? = null

  val clazz: Class<*> get() {
    val result = privateClazz
    if (result != null) {
      return result
    }

    VirtualMachine.nativeGetMethodCoreMetadata(this)
    return privateClazz!!
  }

  val name: String get() {
    val result = privateName
    if (result != null) {
      return result
    }

    VirtualMachine.nativeGetMethodCoreMetadata(this)
    return privateName!!
  }

  val signature: String get() {
    val result = privateSignature
    if (result != null) {
      return result
    }

    VirtualMachine.nativeGetMethodCoreMetadata(this)
    return privateSignature!!
  }

  /**
   * The start of the method
   */
  val startLocation get(): Location {
    val result = privateStartLocation
    if (result != Long.MIN_VALUE) {
      return Location(this, result)
    }

    VirtualMachine.nativeGetMethodCoreMetadata(this)
    return Location(this, privateStartLocation)
  }

  val argsSize get(): Int {
    val result = privateArgsSize
    if (result != Int.MIN_VALUE) {
      return result
    }

    VirtualMachine.nativeGetMethodCoreMetadata(this)
    return privateArgsSize
  }

  val maxLocals get(): Int {
    val result = privateMaxLocals
    if (result != Int.MIN_VALUE) {
      return result
    }

    VirtualMachine.nativeGetMethodCoreMetadata(this)
    return privateMaxLocals
  }

  val arguments: List<LocalVariable<*>> get() {
    try {
      return variables.filter { it.slot >= maxLocals - argsSize }
    } catch (e: JvmtiException) {
      if (e.errorCode != JVMTI_ERROR_ABSENT_INFORMATION) {
        throw e
      }

      val method = VirtualMachine.nativeToReflectedMethod(clazz, methodId, false) as java.lang.reflect.Method
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

  val variables: List<LocalVariable<*>> get() {
    val result = privateVariables
    if (result != null) {
      return result
    }

    privateVariables = VirtualMachine.nativeGetLocalVariables(methodId).toList()

    return privateVariables!!
  }

  fun variablesByName(name: String): List<LocalVariable<*>> {
    return variables.filter { it.name == name }
  }

  fun <T> argumentByName(name: String): LocalVariable<T> {
    return highlander(arguments.filter { it.name == name }) as LocalVariable<T>
  }

  companion object {
    private val cache = LruCache<JMethodId, Method>(8192)

    @Synchronized
    operator fun get(methodId: JMethodId): Method {
      var method = cache[methodId]
      if (method == null) {
        method = Method(methodId)
        cache[methodId] = method
      }

      return method
    }
  }
}