package com.square.stoic.jvmti

/**
 * Analogous to https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/com/sun/jdi/ClassType.html
 */
class ClassType(clazz: Class<*>): ReferenceType(clazz) {
  fun concreteMethodByName(name: String, signature: String) {
    TODO()
  }
}