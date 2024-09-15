package mill.integration

import java.util.regex.Pattern
import scala.util.Try
import utest.assert

object GenIdeaUtils {

  /**
   * The resource content will loaded from the claspath and matched against the file.
   * It may contain the `<!-- IGNORE -->` String, to simulate wildcard-matches.
   */
  def assertIdeaXmlResourceMatchesFile(
      workspacePath: os.Path,
      resource: os.SubPath
  ): Unit = {
    val expectedResourcePath = workspacePath / "idea" / resource
    val actualResourcePath = workspacePath / ".idea" / resource

    val check = Try {
      val expectedResourceString = os.read.lines(expectedResourcePath).mkString("\n")
      val actualResourceString = normaliseLibraryPaths(os.read(actualResourcePath), workspacePath)

      assertPartialContentMatches(
        found = actualResourceString,
        expected = expectedResourceString,
        resource.toString()
      )
    }
    println(
      s"Checking ${expectedResourcePath.relativeTo(workspacePath)} ... ${
          if (check.isSuccess) "OK"
          else "FAILED"
        }"
    )
    check.get
  }

  def assertPartialContentMatches(found: String, expected: String, context: String = ""): Unit = {
    if (!expected.contains(ignoreString)) {
      assert(context != null && found == expected)
    }

    val pattern =
      "(?s)^\\Q" + expected.replaceAll(Pattern.quote(ignoreString), "\\\\E.*\\\\Q") + "\\E$"
    assert(Pattern.compile(pattern).matcher(found).matches())
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
  }

  val ignoreString = "<!-- IGNORE -->"
}
