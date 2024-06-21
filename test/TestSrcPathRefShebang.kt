#!/bin/sh
/*polyglot-trick/
stoic shebang --allow-stoic-options pkg,host,no-host,restart -- "$0" "$@"
exit 0
*/

package shebang

import android.app.Application
import com.square.stoic.print.*
import com.square.stoic.isAndroid
import com.square.stoic.stoic
import com.square.stoic.jvmti

@Suppress("UNUSED_PARAMETER")
fun main(args: List<String>): Int {
  val pkg = if (isAndroid) {
    val apps = jvmti.instances(Application::class.java)
    apps[0].packageName
  } else {
    "jvm"
  }
  println("pkg: $pkg, ${stoic.shebangSrcPath}")
  return 0
}
