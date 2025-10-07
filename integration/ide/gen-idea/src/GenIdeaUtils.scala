package mill.integration

import mill.testkit.{asTestValue, withTestClues}

import java.util.regex.Pattern
import scala.util.Try

object GenIdeaUtils {

  private lazy val coursierVersion = sys.props.getOrElse(
    "mill.integration.coursier-version",
    sys.error("Java property mill.integration.coursier-version not set")
  )

  /**
   * The resource content will be loaded from the classpath and matched against the file.
   * It may contain the `<!-- IGNORE -->` String, to simulate wildcard-matches.
   */
  def assertIdeaXmlResourceMatchesFile(
      workspaceSourcePath: os.Path,
      workspacePath: os.Path,
      resource: os.SubPath
  )(using reporter: utest.framework.GoldenFix.Reporter): Unit = {
    val expectedResourcePath = workspaceSourcePath / "idea" / resource
    val actualResourcePath = workspacePath / ".idea" / resource

    withTestClues(
      asTestValue(expectedResourcePath),
      asTestValue(actualResourcePath)
    ) {
      println(s"Checking ${expectedResourcePath.relativeTo(workspaceSourcePath)} ...")
      val actualResourceString = normaliseLibraryPaths(os.read(actualResourcePath), workspacePath)
        // Normalize jansi jar name because it differs across Linux/OS-X/Winows
        .replace("jansi-2.4.1.jar", "jansi.jar")
        // Normalize coursier cache references which contain the user-specific home folder
        .replaceAll(
          "path=\"[a-zA-Z0-9._/]+/maven2/",
          "path=\".../"
        )
        .replaceAll(
          "-Xplugin:/[a-zA-Z0-9._/]+/maven2/",
          "-Xplugin:.../"
        )

      utest.assertGoldenFile(
        actualResourceString,
        expectedResourcePath.toNIO
      )
    }
  }

  def partialContentMatches(found: String, expected: String, context: String = ""): Boolean =
    (expected.contains(ignoreString) || (context != null && found == expected)) && {
      val pattern =
        "(?s)^\\Q" + expected.replaceAll(Pattern.quote(ignoreString), "\\\\E.*\\\\Q") + "\\E$"
      Pattern.compile(pattern).matcher(found).matches()
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

  val ignoreString = "<!-- IGNORE -->"
}
