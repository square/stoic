package com.squareup.stoic.common

class PluginParsedArgs(
  val pkg: String,
  val restartApp: Boolean,
  val startIfNeeded: Boolean,
  val pluginModule: String,
  val pluginArgs: List<String>,
  val pluginEnvVars: Map<String, String>,
) {
  companion object {
    fun parse(mainParsedArgs: MainParsedArgs): PluginParsedArgs {
      var restartApp = false
      var startIfNeeded = false
      var devMode = false
      var shebangMode = false
      var runInHost = false
      val pluginEnvVars = mutableMapOf<String, String>()

      var runInPkg: String? = null
      var i = -1
      while (++i < mainParsedArgs.stoicArgs.size) {
        when (val arg = mainParsedArgs.stoicArgs[i]) {
          "--restart", "-r" -> {
            restartApp = true
          }
          "--start", "--start-if-needed", "-s" -> {
            startIfNeeded = true
          }
          "--package", "--pkg", "-p" -> {
            runInPkg = mainParsedArgs.stoicArgs[++i]
          }
          "--dev" -> {
            devMode = true
          }
          "--shebang" -> {
            shebangMode = true
          }
          "--host" -> {
            runInHost = true
          }
          "--no-host" -> {
            runInHost = false
          }
          "--env" -> {
            // param is of the form NAME=value, identifying an env var that should be passed to the
            // plugin
            val param = mainParsedArgs.stoicArgs[++i]
            val equalIndex = param.indexOf('=')
            val name = param.substring(0, equalIndex)
            val value = param.substring(equalIndex + 1)
            pluginEnvVars[name] = value
          }
          else -> throw IllegalArgumentException("Unrecognized argument: $arg")
        }
      }

      val pkg = runInPkg ?: System.getenv("STOIC_PKG") ?: stoicDemoAppWithoutSdk

      // The demo app package implicitly sets startIfNeeded for ease of use
      if (pkg == stoicDemoAppWithoutSdk) {
        startIfNeeded = true
      }

      logDebug {"""
        pkg=$pkg
        restartApp=$restartApp
        startIfNeeded=$startIfNeeded
        devMode=$devMode
        shebangMode=$shebangMode
        runInHost=$runInHost
        pluginEnvVars={${pluginEnvVars.map { (key, value) ->
            "$key=$value"
          }.joinToString(", ")}}
      """.trimIndent()}

      return PluginParsedArgs(
        pkg = pkg,
        restartApp = restartApp,
        startIfNeeded = startIfNeeded,
        pluginModule = mainParsedArgs.command,
        pluginArgs = mainParsedArgs.commandArgs,
        pluginEnvVars = pluginEnvVars,
      )
    }
  }
}