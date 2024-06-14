package com.square.stoic.jvmti

typealias OnMethodExit = (frame: StackFrame, wasPoppedByException: Boolean) -> Unit

class MethodExitRequest(val thread: Thread, val callback: OnMethodExit): EventRequest() {
}