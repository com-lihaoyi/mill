package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.{assertGoldenLiteral, _}

/**
 * Tests the `mill-repositories` configuration which allows configuring global repositories
 * for resolving Mill's own JARs (daemon, JVM index) as well as using these as defaults
 * for CoursierModule.
 */
object GlobalRepositoriesTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("globalRepositoriesFullTest") - integrationTest { tester =>
      import tester._

      val localRepoEnv = sys.env.get("MILL_LOCAL_TEST_REPO")
      if (localRepoEnv.isEmpty) {
        println("Skipping test: MILL_LOCAL_TEST_REPO not set")
      } else {
        val localRepoPaths = localRepoEnv.get.split(java.io.File.pathSeparator).map(os.Path(_))

        val customRepo = workspacePath / "custom-local-repo"
        os.makeDir.all(customRepo)

        for (localRepoPath <- localRepoPaths if os.exists(localRepoPath)) {
          os.copy(localRepoPath, customRepo, mergeFolders = true, createFolders = true)
        }

        val buildFile = workspacePath / "build.mill"
        val buildContent = os.read(buildFile)
        val customRepoUri = customRepo.toNIO.toUri.toString
        val newContent = s"""//| mill-repositories: ["$customRepoUri"]
                            |$buildContent""".stripMargin
        os.write.over(buildFile, newContent)

        val daemonCacheDir = workspacePath / "out" / "mill-daemon" / "cache"
        if (os.exists(daemonCacheDir)) {
          os.remove.all(daemonCacheDir)
        }

        val resolveResult = eval(("resolve", "_"))
        assert(resolveResult.isSuccess)

        val fooClasspath = eval(("show", "foo.compileClasspath"))
        assert(fooClasspath.isSuccess)
        assertGoldenLiteral(
          fooClasspath.out.linesIterator.toSeq.filter(_.contains("custom-local-repo")),
          Seq()
        )

        val metaClasspath = eval(("--meta-level", "1", "show", "compileClasspath"))
        assert(metaClasspath.isSuccess)
        val metaLines = metaClasspath.out.linesIterator.toSeq
          .filter(_.contains("custom-local-repo"))
          .map(_.split("/custom-local-repo/").last)
        assertGoldenLiteral(
          metaLines,
          List()
        )

        val daemonClasspathCache = daemonCacheDir / "mill-daemon-classpath"
        assert(os.exists(daemonClasspathCache))
        val cacheContent = os.read(daemonClasspathCache)
        val cacheKeyLine = cacheContent.linesIterator.toSeq.headOption.getOrElse("")
        assert(cacheKeyLine.contains("custom-local-repo"))
      }
    }
  }
}
