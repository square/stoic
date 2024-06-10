package com.square.stoic.jvmti

/**
 * Analog of https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/com/sun/jdi/event/EventQueue.html
 *
 * Since we send events on the thread that generates them, we provide a callback interface rather
 * than a queue. This is more powerful/convenient, since you can access thread-locals and make
 * synchronous calls from that thread.
 */
interface EventCallback {
  /**
   * We provide the set of events occurring simultaneously. The other events' callbacks will still
   * be invoked. If there are no simultaneous events, then this will just be a set of 1 element.
   */
  fun onEvent(bpContext: BreakpointContext, events: Iterable<EventRequest>)
}