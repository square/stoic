package com.square.stoic.jvmti


// a jmethodID
typealias MethodId = Long

external fun <T> nativeGetInstances(clazz: Class<T>, includeSubclasses: Boolean): Array<T>
external fun nativeGetMethodId(methodName: String, methodSignature: String): MethodId