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
    logBlock(DEBUG, { "HostPluginClient.adbShell [$cmd]" }) {
      return ProcessBuilder(listOf("adb", "shell", cmd))
    }
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
        ProcessBuilder("adb", "shell", "mkdir -p $stoicDeviceDevJarDir/")
        ProcessBuilder(
          "adb", "push", "--sync", "$stoicHostUsrPluginSrcDir/$pluginModule/build/libs/$pluginDexJar",
          "$stoicDeviceDevJarDir/$pluginDexJar"
        )
          .inheritIO()
          .waitFor(0)

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
      var stoicArgsString = "--log ${minLogLevel.level} --pkg ${args.pkg}"
      if (args.restartApp) {
        stoicArgsString += " --restart"
      }

      val pluginArgsString = args.pluginArgs.joinToString(" ") {
        // TODO: escape each arg
        it
      }

      // Or maybe we provide a `stoic exec` that will delegate to a shell script which will use printf
      // to escape args correctly

      return adbShellPb(
        "$stoicDeviceSyncDir/bin/stoic $stoicArgsString ${args.pluginModule} $pluginArgsString"
      ).inheritIO().start().waitFor()
    }
  }
}