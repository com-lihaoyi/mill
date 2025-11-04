package mill.integration

import utest.*

object MillInitSbtTests extends MillInitImportTestSuite {
  def tests = Tests {
    test("airstream") - checkImport(
      gitUrl = "https://github.com/raquo/Airstream.git",
      gitBranch = "v17.2.1",
      configsGoldenFile = "golden/sbt/airstream",
      passingTasks = Seq("[3.3.3].compile")
    )

    test("fs2") - checkImport(
      gitUrl = "https://github.com/typelevel/fs2.git",
      gitBranch = "v3.12.0",
      configsGoldenFile = if (System.getenv("CI") == null) "golden/sbt/fs2" else null,
      passingTasks = Seq(
        ("core.js[2.13.16].test.testOnly", "fs2.hashing.HashingSuite"),
        ("core.jvm[3.3.5].test.testOnly", "fs2.hashing.HashingSuite")
      )
    )
  }
}
