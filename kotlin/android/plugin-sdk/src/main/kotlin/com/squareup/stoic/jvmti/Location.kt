package com.squareup.stoic.jvmti

/**
 * Analogous to https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/com/sun/jdi/Location.html
 */
class Location(val method: JvmtiMethod, val jlocation: JLocation) {

}