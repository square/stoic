package com.square.stoic.jvmti

/**
 * Analogous to https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/com/sun/jdi/request/EventRequestManager.html
 */
class EventRequestManager {
  private val breakpointRequests = mutableMapOf<Pair<JMethodId, JLocation>, MutableList<BreakpointRequest>>()

  @Synchronized
  fun createBreakpointRequest(location: Location, callback: EventCallback): BreakpointRequest {
    val key = Pair(location.method.jmethodId, location.jlocation)
    var list = breakpointRequests[key]
    if (list == null) {
      list = mutableListOf<BreakpointRequest>()
      breakpointRequests[key] = list
    }
    val request = BreakpointRequest(location, callback)

    if (list.size == 0) {
      VirtualMachine.nativeSetBreakpoint(location.method.jmethodId, location.jlocation)
    }

    list.add(request)

    return request
  }

  fun deleteEventRequest(request: EventRequest) {
    when (request) {
      is BreakpointRequest -> deleteBreakpointRequest(request)
      else -> TODO()
    }
  }

  @Synchronized
  fun deleteBreakpointRequest(request: BreakpointRequest) {
    val location = request.location
    val key = Pair(location.method.jmethodId, location.jlocation)
    val list = breakpointRequests[key]

    // You shouldn't be able to delete a breakpoint unless you never set it before
    check(list != null)

    val sizeBefore = list.size

    list.remove(request)

    if (sizeBefore != 0 && list.size == 0) {
      VirtualMachine.nativeClearBreakpoint(location.method.jmethodId, location.jlocation)
    }
  }

  fun onBreakpoint(jMethodId: JMethodId, jLocation: JLocation) {
    var requests: MutableList<BreakpointRequest>
    synchronized(this) {
      val key = Pair(jMethodId, jLocation)
      // This should always be non-null because we should only be getting callbacks for requests we
      // set previously. Due to race conditions it might be empty
      requests = breakpointRequests[key]!!
    }

    for (request in requests) {
      request.callback.onEvent(requests.asIterable())
    }
  }
}