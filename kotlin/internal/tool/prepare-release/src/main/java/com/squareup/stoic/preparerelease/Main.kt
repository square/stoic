package com.squareup.stoic.preparerelease

import com.squareup.stoic.bridge.versionCodeFromVersionName

import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
  val stoicDir = File(File(args[0]).absolutePath)
  println("stoicDir: $stoicDir")
  val releasesDir = stoicDir.resolve("releases")
  releasesDir.mkdir()
  val outRelDir = stoicDir.resolve("out/rel")

  val versionFile = stoicDir.resolve("prebuilt/STOIC_VERSION")
  val currentVersion = versionFile.readText().trim()
  validateSemver(currentVersion)

  require(currentVersion.endsWith("-SNAPSHOT")) {
    "Current version must end in -SNAPSHOT (was '$currentVersion')"
  }

  val releaseVersion = currentVersion.removeSuffix("-SNAPSHOT")
  require(versionCodeFromVersionName(releaseVersion) == versionCodeFromVersionName(currentVersion) + 1)

  val postReleaseVersion = incrementSemver(releaseVersion)
  require(versionCodeFromVersionName(postReleaseVersion) == versionCodeFromVersionName(releaseVersion) + 9)

  val releaseTar = releasesDir.resolve("stoic-$releaseVersion.tar.gz")
  if (releaseTar.exists()) {
    System.err.println("\n$releaseTar already exists - aborting\n")
    exitProcess(1)
  }

  // TODO: verify git is clean

  println("running test/regression-check.sh... (this will take a while)")
  check(ProcessBuilder("$stoicDir/test/regression-check.sh").inheritIO().start().waitFor() == 0)
  println("... test/regression-check.sh completed successfully.")

  println("building clean...")
  check(ProcessBuilder("rm", "-r", "$stoicDir/out").inheritIO().start().waitFor() == 0)
  check(ProcessBuilder("$stoicDir/build.sh").inheritIO().start().waitFor() == 0)
  println("... done building clean.")

  println("preparing tar archive...")
  check(
    ProcessBuilder(
      "tar",
      "--create",
      "--gzip",
      "--file=${releaseTar.absolutePath}",
      "--directory=${outRelDir.absolutePath}",
      "."
    ).inheritIO().start().waitFor() == 0
  )
  println("... done preparing tar archive.")

  versionFile.writeText("$postReleaseVersion\n")

  println(
    """
      Release prepared successfully: $releaseTar
      Version incremented to: $postReleaseVersion
    """.trimIndent()
  )
  // TODO:
  // prompt the user to commit/push the changes to the version file and
  //   upload the release
}

fun validateSemver(version: String): Boolean =
  Regex("""^\d+\.\d+\.\d+(-SNAPSHOT)?$""").matches(version)

fun incrementSemver(version: String): String {
  val clean = version.removeSuffix("-SNAPSHOT")
  val parts = clean.split(".").map { it.toInt() }
  return "${parts[0]}.${parts[1]}.${parts[2] + 1}-SNAPSHOT"
}
