@file:OptIn(ExperimentalSerializationApi::class)

package com.squareup.stoic.host.main

import com.squareup.stoic.common.LogLevel
import com.squareup.stoic.common.Sha
import com.squareup.stoic.common.logBlock
import com.squareup.stoic.d8pm.d8PreserveManifest
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.FileOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText

object DexJarCache {
  private const val VERSION = 1

  /** Root directory (${java.io.tmpdir}/dex_cache) **/
  private val cacheRoot: Path =
    Paths.get(System.getProperty("java.io.tmpdir"), ".stoic/cache/jar-$VERSION")

  init {
    Files.createDirectories(cacheRoot)
  }

  fun resolve(jarFile: File): Pair<File, String> {
    val keyDir = computeKeyDir(jarFile)
    get(keyDir, jarFile)?.let { return it }

    val dexJar = jarFileToDexJarFile(keyDir, jarFile)
    Files.createDirectories(keyDir)
    val metaFile = keyDir.resolve("meta")
    metaFile.deleteIfExists()

    if (dexJar.toFile().canonicalPath != jarFile.canonicalPath) {
      // The input jarFile is not a .dex.jar
      dexJar.deleteIfExists()
      val tmpDir = createTempDirectory("stoic-plugin-").toFile()
      logBlock(LogLevel.INFO, { "dexing $jarFile" }) {
        d8PreserveManifest(jarFile, dexJar.toFile(), tmpDir)
      }
    }

    val dexJarSha256Sum = updateMeta(keyDir, jarFile)
    return Pair(dexJar.toFile(), dexJarSha256Sum)
  }

  /**
   * Return the cached dex.jar for [jarFile] if present *and* still valid.
   * Returns `null` on cache miss.
   */
  private fun get(keyDir: Path, jarFile: File): Pair<File, String>? {
    val metaFile = keyDir.resolve("meta")
    if (!metaFile.exists()) {
      return null
    }

    val meta = Json.decodeFromString<DexJarCacheMeta>(metaFile.readText())
    val dexJar = jarFileToDexJarFile(keyDir, jarFile)
    val currentCTime = retrieveCTime(Paths.get(meta.canonicalPath))
    if (currentCTime == meta.posixCTime) {
      return Pair(dexJar.toFile(), meta.dexJarSha256Sum)
    } else {
      return null
    }
  }

  private fun jarFileToDexJarFile(keyDir: Path, jarFile: File): Path {
    val canonicalPath = jarFile.canonicalPath
    val canonicalFile = File(canonicalPath)
    if (canonicalFile.name.endsWith(".dex.jar")) {
      return jarFile.toPath()
    } else {
      return keyDir.resolve("${canonicalFile.nameWithoutExtension}.dex.jar")
    }
  }

  private fun updateMeta(keyDir: Path, jarFile: File): String {
    val dexJar = jarFileToDexJarFile(keyDir, jarFile)
    check(dexJar.exists())

    val canonicalPath = jarFile.canonicalPath
    val posixCTime = retrieveCTime(Paths.get(canonicalPath))
    val dexJarSha256Sum = Sha.computeSha256Sum(dexJar.readBytes())

    val metaFile = keyDir.resolve("meta")
    FileOutputStream(metaFile.toFile()).use {
      Json.encodeToStream(
        DexJarCacheMeta(
          canonicalPath = canonicalPath,
          posixCTime = posixCTime,
          dexJarSha256Sum = dexJarSha256Sum,
        ),
        it
      )
    }

    return dexJarSha256Sum
  }

  fun computeKeyDir(jar: File): Path {
    val hash = Sha.computeSha256Sum(jar.canonicalPath)

    return cacheRoot.resolve(hash)
  }

  /** True POSIX ctime via the "unix" attribute view. */
  private fun retrieveCTime(path: Path): Long {
    val t = Files.getAttribute(path, "unix:ctime") as FileTime
    return t.toMillis()
  }
}

@Serializable
data class DexJarCacheMeta(
  val canonicalPath: String,
  val posixCTime: Long,
  val dexJarSha256Sum: String,
)

