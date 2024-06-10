import android.content.ContextWrapper
import com.square.stoic.helpers.*
import com.square.stoic.jvmti.BreakpointContext
import com.square.stoic.jvmti.Method
import com.square.stoic.jvmti.VirtualMachine
import com.square.stoic.threadlocals.jvmti
import com.square.stoic.threadlocals.stoic
import java.util.concurrent.CountDownLatch

fun main(args: Array<String>) {
  if (args.isEmpty()) {
    eprintln("Usage: stoic breakpoint signature1 signature2 ...")
    eprintln("Each signature should adhere to the JVM signature spec.")
    eprintln("e.g. 'java/util/Map.get(Ljava/util/Object;)Ljava/util/Object;'")
    eprintln("you will probably need to quote so that your shell doesn't interpret treat the")
    eprintln("';' / '(' / ')' chars specially.")
    exit(1)
  }

  for (arg in args) {
    jvmti.syncBreakpoint(sigToMethodId(arg).location()) { context ->
      val sb = StringBuilder("$arg\n")
      for (frame in context.getStackTrace()) {
        sb.append("\tat $frame\n")
      }

      println(sb)
    }
  }

  // Log breakpoints until CTRL+C
  CountDownLatch(1).await()
}

fun sigToMethodId(sig: String): Method {
  val match = Regex("""([^.]+)\.(\w+)(\([^()]*\)[^()]*)""").matchEntire(sig)
  check(match != null)
  val className = match.groupValues[1].replace('/', '.')
  val methodName = match.groupValues[2]
  val methodSig = match.groupValues[3]
  val clazz = Class.forName(className)
  return jvmti.virtualMachine.concreteMethodByName(clazz, methodName, methodSig)
}