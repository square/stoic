package com.square.stoic.jvmti

import com.square.stoic.LruCache
import java.lang.reflect.Modifier

class JvmtiField private constructor(val clazz: Class<*>, val fieldId: JFieldId) {
  // These are fetched by nativeGetFieldCoreMetadata
  private var privateName: String? = null
  private var privateSignature: String? = null
  private var privateGeneric: String? = null
  private var privateModifiers: Int = -1

  val name: String get() {
    val result = privateName
    if (result != null) {
      return result
    }

    VirtualMachine.nativeGetFieldCoreMetadata(this)
    return privateName!!
  }

  val signature: String get() {
    val result = privateSignature
    if (result != null) {
      return result
    }

    VirtualMachine.nativeGetFieldCoreMetadata(this)
    return privateSignature!!
  }

  val modifiers: Int get() {
    val result = privateModifiers
    if (result != -1) {
      return result
    }

    VirtualMachine.nativeGetFieldCoreMetadata(this)
    return privateModifiers
  }

  fun get(obj: Any?): Any? {
    return VirtualMachine.nativeToReflectedField(clazz, fieldId, Modifier.isStatic(modifiers)).get(obj)
  }

  fun set(obj: Any?, value: Any?) {
    VirtualMachine.nativeToReflectedField(clazz, fieldId, Modifier.isStatic(modifiers)).set(obj, value)
  }

  companion object {
    private val cache = LruCache<JFieldId, JvmtiField>(8192)

    @Synchronized
    operator fun get(clazz: Class<*>, fieldId: JFieldId): JvmtiField {
      var field = cache[fieldId]
      if (field == null) {
        field = JvmtiField(clazz, fieldId)
        cache[fieldId] = field
      }

      return field
    }
  }
}