package com.squareup.stoic.android.client

import com.squareup.stoic.common.runAsCompat
import com.squareup.stoic.common.runCommand
import java.io.InputStream
import java.io.OutputStream
import java.lang.ProcessBuilder.Redirect

fun openCrossUidFileOutputStream(pkg: String, path: String, perms: String? = null): OutputStream {
  val proc = ProcessBuilder(runAsCompat, pkg, "sh", "-c", "cat > ${shellSingleQuote(path)}")
    .inheritIO()
    .redirectInput(Redirect.PIPE)
    .start()

  return object : OutputStream() {
    val delegate = proc.outputStream
    override fun write(b: Int) = delegate.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
    override fun flush() = delegate.flush()
    override fun close() {
      delegate.close()
      proc.waitFor() // reap the process
      if (perms != null) {
        runCommand(listOf(runAsCompat, pkg, "chmod", perms, path))
      }
    }
  }
}

fun crossUidFileExists(pkg: String, path: String): Boolean {
  return ProcessBuilder(runAsCompat, pkg, "sh", "-c", "[ -e ${shellSingleQuote(path)} ]")
    .inheritIO()
    .start()
    .waitFor() == 0
}

const val SINGLE_QUOTE = "'"
const val DOUBLE_QUOTE = "\""
const val SINGLE_QUOTE_SEQUENCE = SINGLE_QUOTE + DOUBLE_QUOTE + SINGLE_QUOTE + DOUBLE_QUOTE + SINGLE_QUOTE

fun shellSingleQuote(str: String): String {
  // Replace every single-quote with the sequence '"'"'
  // i.e. we terminate the single-quoted string, add a double-quoted string consisting of only a
  // single-quote, then start a new single-quoted string
  val quoted = str.replace("'", SINGLE_QUOTE_SEQUENCE)

  // The result needs to be enclosed in single-quotes to be valid
  return "'$quoted'"
}
