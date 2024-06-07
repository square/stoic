package com.square.stoic.jvmti

/**
 * Analogous to https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/com/sun/jdi/VirtualMachine.html
 */
class VirtualMachine {
  val eventRequestManager: EventRequestManager = EventRequestManager()

  fun allClasses(): List<ReferenceType> {
    TODO()
  }

  fun classesByName(name: String): List<ReferenceType> {
    // The JDWP implementation just calls GetLoadedClasses and iterates through them
    // https://cs.android.com/android/platform/superproject/main/+/main:external/oj-libjdwp/src/share/back/VirtualMachineImpl.c;l=104
    //
    // But GetLoadedClasses just returns an array of jclass. Seems like we could just call
    // Class.forName instead.
    TODO()
  }
}