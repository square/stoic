package com.squareup.stoic.preparerelease

import com.squareup.stoic.bridge.versionCodeFromVersionName

import java.io.File
import java.lang.ProcessBuilder.Redirect
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

  println("Removing $stoicDir/out to ensure clean build...")
  check(ProcessBuilder("rm", "-r", "$stoicDir/out").inheritIO().start().waitFor() == 0)

  println("Updating ${versionFile.absolutePath} to $releaseVersion")
  versionFile.writeText("$releaseVersion\n")
  try {
    println("running test/clean-build-and-regression-check.sh... (this will take a while)")
    check(
      ProcessBuilder(
        "$stoicDir/test/clean-build-and-regression-check.sh"
      ).inheritIO().start().waitFor() == 0
    )
    println("... test/clean-build-and-regression-check.sh completed successfully.")

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
  } catch (e: Throwable) {
    println("Restoring ${versionFile.absolutePath} to $currentVersion due to failure")
    versionFile.writeText("$currentVersion\n")
    throw e
  }

  println("Updating ${versionFile.absolutePath} to $postReleaseVersion")
  versionFile.writeText("$postReleaseVersion\n")

  val sha256sum = ProcessBuilder("sha256sum", releaseTar.absolutePath)
    .inheritIO()
    .redirectOutput(Redirect.PIPE)
    .start()
    .also { check(it.waitFor() == 0) }
    .inputReader().readText().trim()

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
      
      # Update the release in https://github.com/block/homebrew-tap
      # The sha256sum for this release is $sha256sum
      # The URL should be:
      # https://github.com/block/stoic/releases/download/v$releaseVersion/stoic-$releaseVersion.tar.gz
      # (verify that after uploading to github)
      
      
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
    System.err.println(output)
    System.err.println("The git repository has uncommitted changes or untracked files. Aborting...")
    exitProcess(1)
  }
}
