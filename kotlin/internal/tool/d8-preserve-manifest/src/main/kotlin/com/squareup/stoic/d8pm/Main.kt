package com.squareup.stoic.d8pm
import com.squareup.stoic.bridge.StoicProperties
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

fun main(args: Array<String>) {
  println("args: ${args.joinToString(", ")}")
  val src = File(args[0])
  val dst = File(args[1])
  val tempDir = Files.createTempDirectory("d8-preserve-manifest").toFile()
  System.err.println("temp-dir: $tempDir")
  d8PreserveManifest(src, dst, tempDir)
  System.err.println("generated: ${dst.absolutePath}")
}

fun d8PreserveManifest(jarFile: File, dexJarFile: File) {
  d8PreserveManifest(jarFile, dexJarFile, tempDir = Files.createTempDirectory("d8-preserve-manifest").toFile())
}

fun d8PreserveManifest(jarFile: File, dexJarFile: File, tempDir: File) {
  val androidBuildToolsVersion = StoicProperties.ANDROID_BUILD_TOOLS_VERSION
  val androidHome = System.getenv("ANDROID_HOME") ?: error("Need to set ANDROID_HOME")
  val d8Path = File("$androidHome/build-tools/$androidBuildToolsVersion/d8")
  val rawDexJar = File(tempDir, "raw.dex.jar")

  check(
    ProcessBuilder(
      d8Path.absolutePath,
      "--min-api", StoicProperties.ANDROID_MIN_SDK.toString(),
      "--output", rawDexJar.absolutePath,
      jarFile.absolutePath
    ).start().waitFor() == 0)

  val manifestPath = "META-INF/MANIFEST.MF"

  JarOutputStream(dexJarFile.outputStream().buffered()).use { outputJarStream ->
    JarFile(rawDexJar).use { dex ->
      if (dex.getEntry(manifestPath) == null) {
        JarFile(jarFile).use { original ->
          val manifestEntry = original.getEntry(manifestPath)
          if (manifestEntry != null) {
            outputJarStream.putNextEntry(JarEntry(manifestPath))
            original.getInputStream(manifestEntry).copyTo(outputJarStream)
            outputJarStream.closeEntry()
          }
        }
      }

      // 1. Copy all entries from the dex.jar
      for (entry in dex.entries()) {
        val newEntry = JarEntry(entry.name)
        outputJarStream.putNextEntry(newEntry)
        dex.getInputStream(entry).copyTo(outputJarStream)
        outputJarStream.closeEntry()
      }
    }
  }
}
