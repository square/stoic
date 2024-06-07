package com.square.stoic.jvmti

/**
 * Analogous to https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/com/sun/jdi/ReferenceType.html
 */
open class ReferenceType(val clazz: Class<*>) {
  fun allMethods(): List<Method> {
    TODO()
  }

  fun methodsByName(name: String): List<Method> {
    // Though JVMTI provides a way to lookup methods, it always looks up *all* methods, unfiltered.
    //
    TODO()
  }

  companion object {
    // No JDI analog
    fun <T> forClass(clazz: Class<T>): ReferenceType {
      if (clazz.isArray) {
        TODO()
      } else if (clazz.isInterface) {
        TODO()
      } else {
        return ClassType(clazz)
      }
    }
  }
}