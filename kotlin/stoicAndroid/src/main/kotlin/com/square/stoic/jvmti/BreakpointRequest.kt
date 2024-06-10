package com.square.stoic.jvmti

/**
 * Analogous to https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/com/sun/jdi/request/EventRequest.html
 */
class BreakpointRequest(val location: Location, callback: EventCallback) : EventRequest(callback) {
}