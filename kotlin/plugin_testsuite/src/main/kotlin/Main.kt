import com.square.stoic.jvmti.JvmtiMethod

fun main(args: Array<String>) {
  testDuplicateArguments()
}

// Verify that we don't include duplicate arguments. The local variable table may contain duplicate
// entries for some slots. If we see duplicate entries, we must prefer ones with non-null names.
// This verifies that we handle it correctly with a method known to suffer from this problem.
fun testDuplicateArguments() {
  val method = JvmtiMethod.bySig(
    "android/view/AccessibilityInteractionController\$AccessibilityNodePrefetcher.prefetchAccessibilityNodeInfos(Landroid/view/View;Landroid/view/accessibility/AccessibilityNodeInfo;Ljava/util/List;)V"
  )
  check(method.arguments.map { it.name } == listOf("this", "view", "root", "outInfos"))
}
