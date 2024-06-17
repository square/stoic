package com.square.stoic.jvmti.magics

import com.square.stoic.jvmti.JvmtiClass
import com.square.stoic.jvmti.magic.MagicFields
import com.square.stoic.jvmti.magic.MagicMethods

val Class<*>.sm: MagicMethods
  get() {
    return MagicMethods(null, JvmtiClass[this])
  }

val Class<*>.sf: MagicFields
  get() {
    return MagicFields(null, JvmtiClass[this])
  }

val Any.m: MagicMethods
  get() {
    return MagicMethods(this, JvmtiClass[this.javaClass])
  }

val Any.f: MagicFields
  get() {
    return MagicFields(this, JvmtiClass[this.javaClass])
  }

class c {
  companion object {
    operator fun get(name: String): Class<*> {
      return Class.forName(name)
    }
  }
}

