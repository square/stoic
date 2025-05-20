package com.squareup.stoic.common

class PithyException(val pithyMsg: String, val exitCode: Int = 1, e: Exception? = null) : Exception(
  // Format with indentation so that multiline messages render nicely in stack traces
  """
    ${pithyMsg.replace("\n", "\n    ")}
  """.replace("\\s*$".toRegex(), ""), e)