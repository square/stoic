package com.square.stoic.host.main

import com.square.stoic.common.LogLevel.DEBUG
import com.square.stoic.common.PithyException
import com.square.stoic.common.PluginClient
import com.square.stoic.common.PluginParsedArgs
import com.square.stoic.common.logBlock
import com.square.stoic.common.logDebug
import com.square.stoic.common.minLogLevel
import com.square.stoic.common.stoicDeviceDevJarDir
import com.square.stoic.common.stoicDeviceSyncPluginDir
import com.square.stoic.common.waitFor
import java.io.File

class HostPluginClient(args: PluginParsedArgs) : PluginClient(args) {

  override fun adbShellPb(cmd: String): ProcessBuilder {
    // TODO: Should we ensure non-root?
    logBlock(DEBUG, { "HostPluginClient.adbShell [$cmd]" }) {
      return ProcessBuilder(listOf("adb", "shell", cmd))
    }
  }

  // Run `adb shell` command as shell. This implementation avoids re-evaluating the input
  fun adbShellShell(cmd: String) {
    val wrappedCmd = """
     |if [ "$(id -u)" -eq 0 ]; then
     |  # Need to unroot
     |  su shell <<'EOF'
     |    $cmd
     |EOF
     |else
     |  # Already not root
     |  # For consistency with the root version, we run it through a shell instance. This way we'd
     |  # catch problems if the cmd contained the line 'EOF'.
     |  sh <<'EOF'
     |    $cmd
     |EOF
     |fi
    """.trimMargin()
    val doubleWrappedCmd = """
     |adb shell <<'WRAPPED_EOF'
     |  $wrappedCmd
     |WRAPPED_EOF
    """.trimMargin()
    logDebug { "doubleWrappedCmd: '''\n$doubleWrappedCmd\n'''" }
    ProcessBuilder(listOf("sh", "-c", doubleWrappedCmd)).inheritIO().waitFor(0)
  }

  override fun resolveStagingPluginModule(pluginModule: String): String {
    logDebug { "Attempting to resolve '$pluginModule'" }
    val usrPluginSrcDir = "$stoicHostUsrPluginSrcDir/$pluginModule"
    val pluginDexJar = "$pluginModule.dex.jar"
    if (File(usrPluginSrcDir).exists()) {
      logBlock(DEBUG, { "Building $usrPluginSrcDir/$pluginModule" }) {
        // TODO: In the future, we should allow building a simple jar and stoic handles packaging it
        // into a dex.jar, as needed
        ProcessBuilder("./gradlew", "--quiet", ":$pluginModule:dexJar")
          .inheritIO()
          .directory(File(stoicHostUsrPluginSrcDir))
          .waitFor(0)
        adbShellShell("mkdir -p $stoicDeviceDevJarDir")
        arsync("$stoicHostUsrPluginSrcDir/$pluginModule/build/libs/$pluginDexJar", "adb:$stoicDeviceDevJarDir/$pluginDexJar")

        return "$stoicDeviceDevJarDir/$pluginDexJar"
      }
    }

    logDebug { "$usrPluginSrcDir does not exist - falling back to prebuilt locations." }

    val usrPluginDexJar = "$stoicHostUsrSyncDir/plugins/$pluginDexJar"
    val corePluginDexJar = "$stoicHostCoreSyncDir/plugins/$pluginDexJar"
    if (File(corePluginDexJar).exists() || File(usrPluginDexJar).exists()) {
      return "$stoicDeviceSyncPluginDir/$pluginDexJar"
    }

    throw PithyException("$pluginModule.dex.jar was not found within at either $usrPluginDexJar or $corePluginDexJar")
  }

  override fun slowPath(): Int {
    logBlock(DEBUG, { "HostPluginClient.slowPath" }) {
      resolveStagingPluginModule(args.pluginModule)
      syncDevice()

      // TODO: other args
      var stoicArgs = mutableListOf("--log", minLogLevel.level.toString(), "--pkg", args.pkg)
      if (args.restartApp) {
        stoicArgs += listOf("--restart")
      }

      // Or maybe we provide a `stoic exec` that will delegate to a shell script which will use printf
      // to escape args correctly

      return adbShellPb(
        shellEscapeCmd(listOf("$stoicDeviceSyncDir/bin/stoic") + stoicArgs + listOf(args.pluginModule) + args.pluginArgs)
      ).inheritIO().start().waitFor()
    }
  }
}
