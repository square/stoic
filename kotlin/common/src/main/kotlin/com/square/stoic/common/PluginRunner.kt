package com.square.stoic.common

// Runs a plugin. This is distinct from a PluginClient, which specifically runs a plugin via
// connecting to the PluginServer
abstract class PluginRunner(mainParsedArgs: MainParsedArgs) {
  val args = PluginParsedArgs.parse(mainParsedArgs)

  abstract fun run(): Int
}