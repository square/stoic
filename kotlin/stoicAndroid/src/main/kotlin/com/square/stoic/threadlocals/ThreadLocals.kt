package com.square.stoic.threadlocals

import com.square.stoic.Stoic
import com.square.stoic.StoicJvmti
import com.square.stoic.internalStoic

// These are in a separate file from Stoic to prevent Stoic APIs from accidentally referencing the
// thread-local.
// TODO: allow access (with heavy warning) from any thread even when the thread-local doesn't have
// a value if there is only one Stoic instance in the whole process.
val stoic: Stoic
  get() = internalStoic.get()!!

val jvmti: StoicJvmti
  get() = stoic.jvmti