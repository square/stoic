package com.square.stoic.android.client

import com.square.stoic.common.MainParsedArgs
import com.square.stoic.common.PithyException
import com.square.stoic.common.PluginRunner
import com.square.stoic.common.logInfo
import com.square.stoic.common.logWarn
import com.square.stoic.common.runCommand
import com.square.stoic.common.stoicExamplePkg
import com.square.stoic.common.stoicDeviceSyncDir
import java.io.File
import java.lang.ProcessBuilder.Redirect

class AndroidPluginRunner(mainParsedArgs: MainParsedArgs) : PluginRunner(mainParsedArgs) {
  override fun run(): Int {
    try {
      return wrappedRun()
    } catch (e: Exception) {
      val pkg = args.pkg
      try {
        runCommand(listOf("dumpsys package $pkg | grep DEBUGGABLE"), shell = true)
      } catch (e: Exception) {
        logWarn {
          """
            |
            |Package $pkg is not debuggable. Stoic has limited support for non-debuggable processes
            |when root is available, but it often leads to weird errors. Try again with a debuggable
            |process?
            |
          """.trimMargin()
        }
      }
      return 1
    }
  }

  private fun wrappedRun(): Int {
    if (args.runInHost) {
      throw PithyException("Option `--host` invalid on Android")
    }

    val pkg = args.pkg

    seLinuxViolationDetector.start(pkg)

    val matches = runCommand(listOf("pm", "list", "package", pkg)).split("\n")
    if (!matches.contains("package:$pkg")) {
      if (pkg == stoicExamplePkg) {
        logInfo { "$stoicExamplePkg appears to not be installed - installing now." }

        // We always install the example pkg if it's requested
        runCommand(
          listOf("pm", "install", "apk/exampleapp-debug.apk"),
          directory = stoicDeviceSyncDir,
          redirectOutput = Redirect.to(File("/dev/null"))
        )
      } else {
        throw PithyException("$pkg not found")
      }
    }

    if (args.runInShell) {
      throw PithyException("TODO")
    } else if (args.runAsRoot) {
      throw PithyException("TODO")
    }

    return AndroidPluginClient(args).run()


  }
}
