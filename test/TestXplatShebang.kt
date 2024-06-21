#!/usr/bin/env stoic shebang --allow-stoic-options pkg,host,no-host --
package shebang

import android.app.Application
import com.square.stoic.print.*
import com.square.stoic.isAndroid
import com.square.stoic.jvmti

fun main(args: List<String>): Int {
  val pkg = if (isAndroid) {
    val apps = jvmti.instances(Application::class.java)
    apps[0].packageName
  } else {
    "jvm"
  }
  println("pkg: $pkg, args: $args")
  return 0
}
