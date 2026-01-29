package mill.integration

import scala.util.Try

object GenIdeaUtils {

  private lazy val coursierVersion = sys.props.getOrElse(
    "mill.integration.coursier-version",
    sys.error("Java property mill.integration.coursier-version not set")
  )

  /**
   * Compares the generated `.idea` folder against the expected `idea` golden folder.
   * Normalizes file contents before comparison to handle platform/environment differences.
   */
  def assertIdeaFolderMatches(
      workspaceSourcePath: os.Path,
      workspacePath: os.Path
  )(using reporter: utest.framework.GoldenFix.Reporter): Unit = {
    val goldenFolderPath = workspaceSourcePath / "idea"
    val actualFolderPath = workspacePath / ".idea"
    val normalizedFolderPath = workspacePath / ".idea-normalized"

    // Copy and normalize all files to a temp folder for comparison
    // Filter out library XML files except scala_* to reduce golden file churn
    os.remove.all(normalizedFolderPath)
    for (file <- os.walk(actualFolderPath) if os.isFile(file)) {
      val relPath = file.subRelativeTo(actualFolderPath)
      val shouldInclude = !isLibraryFile(relPath) || isScalaLibraryFile(relPath)
      if (shouldInclude) {
        val destPath = normalizedFolderPath / relPath
        os.makeDir.all(destPath / os.up)
        val normalizedContent = normalizeIdeaFileContent(os.read(file), workspacePath)
        os.write(destPath, normalizedContent)
      }
    }

    utest.assertGoldenFolder(normalizedFolderPath.toNIO, goldenFolderPath.toNIO)
  }

  /** Check if a file is in the libraries folder */
  private def isLibraryFile(path: os.SubPath): Boolean = {
    path.segments.contains("libraries") && path.last.endsWith(".xml")
  }

  /** Check if a library file is a scala_* file (e.g., scala_SDK_*, scala_library_*, scala3_library_*) */
  private def isScalaLibraryFile(path: os.SubPath): Boolean = {
    val fileName = path.last
    fileName.startsWith("scala_") || fileName.startsWith("scala3_")
  }

  private def normalizeIdeaFileContent(content: String, workspacePath: os.Path): String = {
    normaliseLibraryPaths(content, workspacePath)
      // Normalize jansi jar name because it differs across Linux/OS-X/Windows
      .replace("jansi-2.4.1.jar", "jansi.jar")
      // Normalize coursier cache references which contain the user-specific home folder
      .replaceAll(
        "path=\"[a-zA-Z0-9._/-]+/maven2/",
        "path=\".../"
      )
      .replaceAll(
        "path=\"[a-zA-Z0-9._/-]+/out/",
        "path=\".../out/"
      )
      .replaceAll(
        "-Xplugin:/[a-zA-Z0-9._/-]+/maven2/",
        "-Xplugin:.../"
      )
      .replaceAll(
        "-Xplugin:/[a-zA-Z0-9._/-]+/out/",
        "-Xplugin:.../"
      )
      // Normalize varying local repo paths (e.g., dist/raw/localRepo.dest vs runner/.../publishLocalTestRepo.dest)
      .replaceAll(
        "\\.\\.\\./[a-zA-Z0-9._/-]+/com/lihaoyi/",
        ".../com/lihaoyi/"
      )
      .replaceAll(
        "/out/[a-zA-Z0-9._/-]+/com/lihaoyi/",
        "/out/localRepo/com/lihaoyi/"
      )
      // Normalize third-party dependency versions in jar paths like "artifact/1.2.3/artifact-1.2.3.jar"
      // Also handles artifacts with Scala version suffixes like "artifact_2.12/1.0.6/artifact_2.12-1.0.6.jar"
      .replaceAll(
        "/([a-zA-Z0-9_.-]+)/[0-9][0-9a-zA-Z._-]*/\\1-[0-9][0-9a-zA-Z._-]*\\.jar",
        "/$1/<version>/$1-<version>.jar"
      )
      // Normalize library names in XML attributes like:
      // name="SBT: com.lihaoyi:fansi_3_3.8:0.5.1:jar" -> name="SBT: com.lihaoyi:fansi_3_3.X:<version>:jar"
      .replaceAll(
        "(name=\"SBT: [^\"]+_3)_([0-9]+\\.[0-9]+):([0-9][0-9a-zA-Z._-]*):jar\"",
        "$1_3.X:<version>:jar\""
      )
      // Normalize Scala 2.x library names like name="SBT: org.scala-lang:scala-library_2.13:2.13.18:jar"
      .replaceAll(
        "(name=\"SBT: [^\"]+_2)\\.[0-9]+:([0-9][0-9a-zA-Z._-]*):jar\"",
        "$1.X:<version>:jar\""
      )
      // Normalize library jar names with semver versions like name="os-zip-0.11.6.jar" or name="scala3-library_3-3.8.1.jar"
      .replaceAll(
        "(name=\"[a-zA-Z0-9_-]+)-[0-9]+\\.[0-9]+\\.[0-9]+\\.jar\"",
        "$1-<version>.jar\""
      )
      // Normalize scala-SDK names like name="scala-SDK-3.8.1"
      .replaceAll(
        "(name=\"scala-SDK)-[0-9]+\\.[0-9]+\\.[0-9]+\"",
        "$1-<version>\""
      )
      // Normalize Scala language level like <language-level>Scala_3_8</language-level>
      .replaceAll(
        "<language-level>Scala_3_[0-9]+</language-level>",
        "<language-level>Scala_3_X</language-level>"
      )
      // Normalize Scala 2 language level like <language-level>Scala_2_13</language-level>
      .replaceAll(
        "<language-level>Scala_2_[0-9]+</language-level>",
        "<language-level>Scala_2_X</language-level>"
      )
  }

  private def normaliseLibraryPaths(in: String, workspacePath: os.Path): String = {
    val coursierPath = os.Path(coursier.paths.CoursierPaths.cacheDirectory())
    val path =
      Try("$PROJECT_DIR$/" + coursierPath.relativeTo(workspacePath)).getOrElse(
        coursierPath
      ).toString().replace(
        """\""",
        "/"
      )

    in.replace(path, "COURSIER_HOME")
      .replace("//$USER_HOME$/AppData/Local/Coursier/cache/", "//$USER_HOME$/COURSIER_CACHE/")
      .replace("//$USER_HOME$/.cache/coursier/", "//$USER_HOME$/COURSIER_CACHE/")
      .replace("//$USER_HOME$/Library/Caches/Coursier/", "//$USER_HOME$/COURSIER_CACHE/")
      .replace(coursierVersion, "COURSIER_VERSION") // this might match too many sub-strings
  }
}
