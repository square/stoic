package com.square.stoic.host.main

import com.square.stoic.common.MainParsedArgs
import com.square.stoic.common.PluginRunner

class HostPluginRunner(mainParsedArgs: MainParsedArgs) : PluginRunner(mainParsedArgs) {
  override fun run(): Int {
    return HostPluginClient(args).run()
  }
}