package mill.integration

import java.util.regex.Pattern
import scala.util.Try
import utest.assert

object GenIdeaUtils {

  /**
   * Set this to true to update the gen-idea tests snapshot data
   */
  def updateResources: Boolean = false

  /**
   * The resource content will be loaded from the classpath and matched against the file.
   * It may contain the `<!-- IGNORE -->` String, to simulate wildcard-matches.
   */
  def assertIdeaXmlResourceMatchesFile(
      workspaceSourcePath: os.Path,
      workspacePath: os.Path,
      resource: os.SubPath
  ): Unit = {
    val expectedResourcePath = workspaceSourcePath / "idea" / resource
    val actualResourcePath = workspacePath / ".idea" / resource

    println(s"Checking ${expectedResourcePath.relativeTo(workspaceSourcePath)} ...")
    val expectedResourceString = os.read.lines(expectedResourcePath).mkString("\n")
    val actualResourceString = normaliseLibraryPaths(os.read(actualResourcePath), workspacePath)

    if (updateResources) {
      val matches = partialContentMatches(
        found = actualResourceString,
        expected = expectedResourceString,
        resource.toString()
      )
      if (!matches) {
        System.err.println(s"Writing $expectedResourcePath")
        os.write.over(expectedResourcePath, actualResourceString)
      }
    } else
      assert(
        partialContentMatches(
          found = actualResourceString,
          expected = expectedResourceString,
          resource.toString()
        )
      )
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
  }

  val ignoreString = "<!-- IGNORE -->"
}
