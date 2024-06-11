import com.square.stoic.helpers.eprintln
import com.square.stoic.helpers.exit
import com.square.stoic.helpers.println
import com.square.stoic.jvmti.Method
import com.square.stoic.threadlocals.jvmti
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
      // Construct the string ahead of time to avoid tearing (which could otherwise happen if
      // multiple threads are writing to stdout simultaneously)
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