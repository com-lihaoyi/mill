package mill.scripts

import utest.*
import scala.util.Using

object ExampleZipTests extends TestSuite {
  def tests = Tests {
    test("exampleZips") {
      val manifestPath = os.Path(sys.env("MILL_EXAMPLE_ZIPS_MANIFEST"))
      val expectedVersion = sys.env("MILL_EXPECTED_VERSION")
      val zipPaths = upickle.default.read[Seq[String]](os.read(manifestPath)).map(os.Path(_))
      assert(zipPaths.nonEmpty)

      zipPaths.foreach { zipPath =>
        Using.resource(os.zip.open(zipPath)) { zipRoot =>
          val topLevelDirs = os.list(zipRoot).filter(os.isDir)
          assert(topLevelDirs.nonEmpty)
          val rootDir = topLevelDirs.head

          val millSh = rootDir / "mill"
          val millBat = rootDir / "mill.bat"
          assert(os.exists(millSh))
          assert(os.exists(millBat))

          val millShContents = os.read(millSh)
          val millBatContents = os.read(millBat)
          assert(millShContents.contains("DEFAULT_MILL_VERSION"))
          assert(millBatContents.contains("DEFAULT_MILL_VERSION"))
          assert(millShContents.contains(expectedVersion))
          assert(millBatContents.contains(expectedVersion))

          val buildMill = rootDir / "build.mill"
          val buildYaml = rootDir / "build.mill.yaml"
          val hasBuildMill = os.exists(buildMill)
          val hasBuildYaml = os.exists(buildYaml)
          assert(hasBuildMill ^ hasBuildYaml)

          if (hasBuildYaml) {
            assert(os.exists(rootDir / "readme.adoc"))
          }
        }
      }
    }
  }
}
