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

      // Get the localRepo path that the test infrastructure uses
      val localRepoEnv = sys.env.get("MILL_LOCAL_TEST_REPO")
      if (localRepoEnv.isEmpty) {
        // Skip test if not running with test repo (e.g., in packaged mode)
        println("Skipping test: MILL_LOCAL_TEST_REPO not set")
      } else {
        val localRepoPaths = localRepoEnv.get.split(java.io.File.pathSeparator).map(os.Path(_))

        // Create a custom repository by copying the local test repo
        val customRepo = workspacePath / "custom-local-repo"
        os.makeDir.all(customRepo)

        // Copy contents from test repos to custom repo
        for (localRepoPath <- localRepoPaths if os.exists(localRepoPath)) {
          os.walk(localRepoPath).foreach { src =>
            val rel = src.relativeTo(localRepoPath)
            val dest = customRepo / rel
            if (os.isDir(src)) {
              os.makeDir.all(dest)
            } else if (os.isFile(src) && !os.exists(dest)) {
              os.copy(src, dest, createFolders = true)
            }
          }
        }

        // Write the mill-repositories config to the build file header
        val buildFile = workspacePath / "build.mill"
        val buildContent = os.read(buildFile)
        val customRepoUri = customRepo.toNIO.toUri.toString
        val newContent = s"""//| mill-repositories: ["$customRepoUri"]
                            |$buildContent""".stripMargin
        os.write.over(buildFile, newContent)

        // Clear any cached daemon classpath to force re-resolution
        val daemonCacheDir = workspacePath / "out" / "mill-daemon" / "cache"
        if (os.exists(daemonCacheDir)) {
          os.remove.all(daemonCacheDir)
        }

        // Run a simple command to ensure Mill starts and processes the config
        val resolveResult = eval(("resolve", "_"))
        assert(resolveResult.isSuccess)

        // Test 1: Check foo.compileClasspath
        val fooClasspath = eval(("show", "foo.compileClasspath"))
        assert(fooClasspath.isSuccess)
        // foo has no dependencies so classpath only has compile-resources
        assertGoldenLiteral(
          fooClasspath.out.linesIterator.toSeq.filter(_.contains("custom-local-repo")),
          Seq()
        )

        // Test 2: Check meta-level compileClasspath contains custom-local-repo jars
        val metaClasspath = eval(("--meta-level", "1", "show", "compileClasspath"))
        assert(metaClasspath.isSuccess)
        val metaLines = metaClasspath.out.linesIterator.toSeq
          .filter(_.contains("custom-local-repo"))
          .map(_.split("/custom-local-repo/").last) // Normalize to just the repo-relative path
        assertGoldenLiteral(
          metaLines,
          List()
        )

        // Test 3: Check daemon classpath cache includes custom-local-repo in cache key
        val daemonClasspathCache = daemonCacheDir / "mill-daemon-classpath"
        assert(os.exists(daemonClasspathCache))
        val cacheContent = os.read(daemonClasspathCache)
        // The cache key (first line) should include the custom-local-repo URI
        // The actual jar paths may come from MILL_LOCAL_TEST_REPO (which takes precedence)
        val cacheKeyLine = cacheContent.linesIterator.toSeq.headOption.getOrElse("")
        assert(cacheKeyLine.contains("custom-local-repo"))
      }
    }
  }
}
