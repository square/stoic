package com.square.stoic.common

class FailedExecException(val exitCode: Int, msg: String): Exception(msg)