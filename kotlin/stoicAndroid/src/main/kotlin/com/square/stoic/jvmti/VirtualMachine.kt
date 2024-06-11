package com.square.stoic.jvmti


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
    val methodId = nativeGetMethodId(clazz, name, signature)
    return Method(clazz, methodId)
  }

  fun methodBySig(sig: String): Method {
    val match = Regex("""([^.]+)\.(\w+)(\([^()]*\)[^()]*)""").matchEntire(sig)
    check(match != null)
    val className = match.groupValues[1].replace('/', '.')
    val methodName = match.groupValues[2]
    val methodSig = match.groupValues[3]
    val clazz = Class.forName(className)
    return concreteMethodByName(clazz, methodName, methodSig)
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
  external fun nativeGetMethodStartLocation(jMethodId: JMethodId): JLocation

  @JvmStatic
  external fun nativeGetMethodEndLocation(jMethodId: JMethodId): JLocation

  @JvmStatic
  external fun nativeSetBreakpoint(jmethodId: JMethodId, jlocation: JLocation)

  @JvmStatic
  external fun nativeClearBreakpoint(jmethodId: JMethodId, jlocation: JLocation)

  @JvmStatic
  external fun nativeGetLocalVariables(jmethodId: JMethodId): Array<LocalVariable<*>>

  @JvmStatic
  external fun nativeGetArgumentsSize(jmethodId: JMethodId): Int

  @JvmStatic
  external fun nativeGetMaxLocals(jmethodId: JMethodId): Int

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

  // Callback from native
  @JvmStatic
  fun nativeCallbackOnBreakpoint(jmethodId: JMethodId, jlocation: JLocation, frameCount: Int) {
    val method = Method(null, jmethodId)
    val location = Location(method, jlocation)
    val frame = StackFrame(Thread.currentThread(), frameCount, location)
    eventRequestManager.onBreakpoint(frame)
  }
}