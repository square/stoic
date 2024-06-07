This is an implementation of something approximating JDI, but instead of being implemented on JDWP
it is implemented directly on top of JVMTI.

It is hoped by using the structure of JDI I avoid running into any design landmines.

The main reason I didn't implement the JDI interfaces directly is that since it's running
in-process, there shouldn't be any need for JDI-style mirrors.

JVMTI/JPDA links
https://www.pnfsoftware.com/blog/debugging-android-apps-on-android-pie-and-above/
https://docs.oracle.com/javase/8/docs/technotes/guides/jpda/jdwp-spec.html
https://docs.oracle.com/javase/8/docs/platform/jpda/jdwp/jdwp-protocol.html

JDWP implementation on top of JVMTI. e.g. setBreakpoint:
https://cs.android.com/android/platform/superproject/main/+/d2f87bde534633e17d5c45f908094d5af66ed7e8:external/oj-libjdwp/src/share/back/eventFilter.c;l=1020

https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/

JDI implementatin on top of JDWP
https://cs.android.com/android/platform/superproject/main/+/main:external/oj-libjdwp/src/share/classes/com/sun/tools/jdi/
https://cs.android.com/android/platform/superproject/main/+/main:out/soong/.intermediates/external/oj-libjdwp/jdwp_generated_java/gen/JDWP.java

How JDI implements BreakpointRequest.addInstanceFilter https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/com/sun/jdi/request/BreakpointRequest.html#addInstanceFilter-com.sun.jdi.ObjectReference-
https://cs.android.com/android/platform/superproject/main/+/main:external/oj-libjdwp/src/share/classes/com/sun/tools/jdi/EventRequestManagerImpl.java;l=304
How JDWP implements it
https://cs.android.com/android/platform/superproject/main/+/main:external/oj-libjdwp/src/share/back/eventFilter.c;l=465
(eventFilterRestricted_passesFilter is called from event_callback which in turn is called by
cbBreakpoint and other actual JVMTI callbacks)
https://cs.android.com/android/platform/superproject/main/+/main:external/oj-libjdwp/src/share/back/eventHandler.c;l=656

