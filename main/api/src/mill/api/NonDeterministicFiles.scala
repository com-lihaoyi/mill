package mill.api

import java.nio.file.attribute.FileTime
import java.time.Instant
import java.nio.file.Files

object NonDeterministicFiles {
  private val nonDeterministicFiles = Set(
    "mill-profile.json",
    "mill-chrome-profile.json"
  )

  private val nonDeterministicDirectories = Set(
    "mill-server",
    "mill-no-server"
  )

  def isNonDeterministic(path: os.Path): Boolean = {
    nonDeterministicFiles.contains(path.last) ||
    path.segments.exists(nonDeterministicDirectories.contains) ||
    path.last.endsWith(".worker.json")
  }

  def normalizeWorkerJson(path: os.Path): os.Path = {
    if (path.ext == "json" && !path.last.endsWith(".worker.json")) {
      path / os.up / (path.last.stripSuffix(".json") + ".worker.json")
    } else {
      path
    }
  }

  def zeroOutModificationTime(path: os.Path): Unit = {
    val zeroTime = FileTime.from(Instant.EPOCH)
    Files.setLastModifiedTime(path.toNIO, zeroTime)
  }
}
