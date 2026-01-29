package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

/**
 * Make sure that it correctly impacts the daemon classpath, the meta-build
 * classpath, and the classpath of foo and any other individual modules
 */
object MillRepositoriesTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    integrationTest { tester =>
      import tester._

      // Set up a custom local repo by copying the Mill artifacts
      val millProjectRoot = os.Path(sys.env("MILL_PROJECT_ROOT"))
      val sourceLocalRepo = millProjectRoot / "out" / "dist" / "raw" / "localRepo.dest"
      val customLocalRepo = workspacePath / "custom-local-repo"
      os.copy(sourceLocalRepo, customLocalRepo)

      val initialize = eval(("version"))
      assert(initialize.isSuccess) // initialize daemon

      val (k, vs) = upickle.read[(String, Seq[String])](
        os.read(workspacePath / "out/mill-daemon/cache/mill-daemon-classpath")
      )
      def findConstants(vs: Seq[String]) = vs
        .filter(_.contains("mill-core-constants"))
        .map(_.split(os.pwd.toString).last)

      // Make sure the various Mill jars all get resolved from `custom-local-repo` rather
      // han the `localRepo.dest` that would be normally be used in the integration tests
      assertGoldenLiteral(
        findConstants(vs),
        List(
          "/custom-local-repo/com/lihaoyi/mill-core-constants/SNAPSHOT/mill-core-constants-SNAPSHOT.jar"
        )
      )

      val metaClasspath = eval(("--meta-level", "1", "show", "compileClasspath"))
      assert(metaClasspath.isSuccess)
      assertGoldenLiteral(
        findConstants(upickle.read[Seq[String]](metaClasspath.out)),
        List(
          "/custom-local-repo/com/lihaoyi/mill-core-constants/SNAPSHOT/mill-core-constants-SNAPSHOT.jar"
        )
      )

      val fooClasspath = eval(("show", "foo.compileClasspath"))
      assert(fooClasspath.isSuccess)
      assertGoldenLiteral(
        findConstants(upickle.read[Seq[String]](fooClasspath.out)),
        List(
          "/custom-local-repo/com/lihaoyi/mill-core-constants/SNAPSHOT/mill-core-constants-SNAPSHOT.jar"
        )
      )
    }
  }
}
