package com.square.stoic.jvmti

typealias OnMethodEntry = (frame: StackFrame) -> Unit

class MethodEntryRequest(val thread: Thread, val callback: OnMethodEntry): EventRequest() {
}