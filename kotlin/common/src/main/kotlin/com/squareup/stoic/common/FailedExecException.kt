package com.squareup.stoic.common

class FailedExecException(val exitCode: Int, msg: String): Exception(msg)