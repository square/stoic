package com.square.stoic.jvmti

/**
 * Analogous to https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/com/sun/jdi/Method.html
 *
 * See https://docs.oracle.com/javase/8/docs/platform/jvmti/jvmti.html#method for JVMTI method
 * functions
 */
class Method(val clazz: Class<*>, val jmethodId: JMethodId) {
  /**
   * The start of the method
   */
  fun location(): Location {
    val jlocation = VirtualMachine.nativeGetMethodStartLocation(jmethodId)
    return Location(this, jlocation)
  }
}