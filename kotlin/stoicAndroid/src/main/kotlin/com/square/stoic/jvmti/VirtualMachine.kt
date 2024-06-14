package com.square.stoic.jvmti

import com.square.stoic.highlander

// a jmethodID
typealias JMethodId = Long

// a jlocation
typealias JLocation = Long


/**
 * Analogous to https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/com/sun/jdi/VirtualMachine.html
 */
object VirtualMachine {
  val eventRequestManager: EventRequestManager = EventRequestManager()

  fun concreteMethodByName(clazz: Class<*>, name: String, signature: String): Method {
    val methods = classMethods(clazz)
    val filteredMethods = methods.filter { it.name == name && it.signature == signature }
    if (filteredMethods.isEmpty()) {
      val filteredByName = methods.filter { it.name == name }
      if (filteredByName.isNotEmpty()) {
        val signatures = filteredByName.map { "${it.signature}\n" }
        throw NoSuchMethodException(
          """
            Method ${clazz.name}.$name$signature does not exist. ${clazz.name}.$name with the
            following signatures exist:
          """.trimIndent() + "\n$signatures")
      } else {
        val methodNames = methods.map { "${it.name}\n" }.toSet()
        throw NoSuchMethodException("""
            Method $clazz.$name does not exist. $clazz has methods with the following names:
          """.trimIndent() + "\n$methodNames")
      }
    }

    return highlander(filteredMethods)
  }

  fun classMethods(clazz: Class<*>): List<Method> {
    return nativeGetClassMethods(clazz).toList()
  }

  fun methodBySig(sig: String): Method {
    val match = Regex("""([^.]+)\.(\w+)(\([^()]*\)[^()]*)""").matchEntire(sig)
    check(match != null) { "Invalid sig: '$sig'" }
    val classSig = match.groupValues[1]
    val methodName = match.groupValues[2]
    val methodSig = match.groupValues[3]
    val clazz = classBySig(classSig)
    return concreteMethodByName(clazz, methodName, methodSig)
  }

  fun classBySig(sig: String): Class<*> {
    val className = sig.replace('/', '.')
    return Class.forName(className)
  }

  fun allClasses(): List<Class<*>> {
    TODO()
  }

//  fun classesByName(name: String): List<ReferenceType> {
    // The JDWP implementation just calls GetLoadedClasses and iterates through them
    // https://cs.android.com/android/platform/superproject/main/+/main:external/oj-libjdwp/src/share/back/VirtualMachineImpl.c;l=104
    //
    // But GetLoadedClasses just returns an array of jclass. Seems like we could just call
    // Class.forName instead.
//    TODO()
//  }

  @JvmStatic
  external fun <T> nativeGetInstances(clazz: Class<T>, includeSubclasses: Boolean): Array<T>

  @JvmStatic
  external fun nativeGetMethodId(clazz: Class<*>, methodName: String, methodSignature: String): JMethodId

  @JvmStatic
  external fun nativeSetBreakpoint(jmethodId: JMethodId, jlocation: JLocation)

  @JvmStatic
  external fun nativeClearBreakpoint(jmethodId: JMethodId, jlocation: JLocation)

  @JvmStatic
  external fun nativeGetLocalVariables(jmethodId: JMethodId): Array<LocalVariable<*>>

  @JvmStatic
  external fun nativeGetMethodCoreMetadata(method: Method)

  @JvmStatic
  external fun nativeGetLocalObject(thread: Thread, height: Int, slot: Int): Any

  @JvmStatic
  external fun nativeGetLocalInt(thread: Thread, height: Int, slot: Int): Int

  @JvmStatic
  external fun nativeGetLocalLong(thread: Thread, height: Int, slot: Int): Long

  @JvmStatic
  external fun nativeGetLocalFloat(thread: Thread, height: Int, slot: Int): Float

  @JvmStatic
  external fun nativeGetLocalDouble(thread: Thread, height: Int, slot: Int): Double

  @JvmStatic
  external fun nativeGetClassMethods(clazz: Class<*>): Array<Method>

  // Returns either java.lang.reflect.Method or java.lang.reflect.Constructor
  @JvmStatic
  external fun nativeToReflectedMethod(clazz: Class<*>, methodId: JMethodId, isStatic: Boolean): Any

  // TODO: the actual API also returns genericSignature - this should probably return a pair of
  // Strings
  @JvmStatic
  external fun nativeGetClassSignature(clazz: Class<*>): String

  @JvmStatic
  external fun nativeMethodEntryCallbacks(thread: Thread, isEnabled: Boolean)

  @JvmStatic
  external fun nativeMethodExitCallbacks(thread: Thread, isEnabled: Boolean)

  // Callback from native
  @JvmStatic
  fun nativeCallbackOnBreakpoint(jmethodId: JMethodId, jlocation: JLocation, frameCount: Int) {
    val method = Method[jmethodId]
    val location = Location(method, jlocation)
    val frame = StackFrame(Thread.currentThread(), frameCount, location)
    eventRequestManager.onBreakpoint(frame)
  }

  @JvmStatic
  fun nativeCallbackOnMethodEntry(jmethodId: JMethodId, jlocation: JLocation, frameCount: Int) {
    val method = Method[jmethodId]
    val location = Location(method, jlocation)
    val frame = StackFrame(Thread.currentThread(), frameCount, location)
    eventRequestManager.onMethodEntry(frame)
  }

  @JvmStatic
  fun nativeCallbackOnMethodExit(
      jmethodId: JMethodId,
      jlocation: JLocation,
      frameCount: Int,
      wasPoppedByException: Boolean) {
    val method = Method[jmethodId]
    val location = Location(method, jlocation)
    val frame = StackFrame(Thread.currentThread(), frameCount, location)
    eventRequestManager.onMethodExit(frame, wasPoppedByException)
  }
}