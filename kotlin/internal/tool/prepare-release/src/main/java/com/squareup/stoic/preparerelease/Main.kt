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

  ensureCleanGitRepo(stoicDir)

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

  val releaseTag = "v$releaseVersion"
  println(
    """
      
      
      
      Release version: $releaseVersion
      Release tag: $releaseTag
      Release prepared successfully: $releaseTar
      Version incremented to: $postReleaseVersion
      
      
      Next steps:
      
      # Make sure you're in the right directory
      cd $stoicDir
      
      # tag the release and push it
      git tag $releaseTag && git push origin $releaseTag
      
      # Commit the version bump and push it
      git add prebuilt/STOIC_VERSION && git commit -m "$postReleaseVersion version bump" && git push
      
      # Upload the release to Github
      gh release create $releaseTag $releaseTar --title $releaseTag
      
      
      
    """.trimIndent()
  )
}

fun validateSemver(version: String): Boolean =
  Regex("""^\d+\.\d+\.\d+(-SNAPSHOT)?$""").matches(version)

fun incrementSemver(version: String): String {
  val clean = version.removeSuffix("-SNAPSHOT")
  val parts = clean.split(".").map { it.toInt() }
  return "${parts[0]}.${parts[1]}.${parts[2] + 1}-SNAPSHOT"
}

fun ensureCleanGitRepo(stoicDir: File) {
  val process = ProcessBuilder("git", "status", "--porcelain")
    .directory(stoicDir)
    .redirectErrorStream(true)
    .start()

  val output = process.inputStream.bufferedReader().readText().trim()
  process.waitFor()

  if (output.isNotEmpty()) {
    System.err.println("The git repository has uncommitted changes or untracked files. Aborting...")
    exitProcess(1)
  }
}