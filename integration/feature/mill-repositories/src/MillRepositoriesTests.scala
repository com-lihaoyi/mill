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
      assertGoldenLiteral(
        vs.collect { case s"$prefix/custom-local-repo/$rest" => rest },
        List(
          "com/lihaoyi/mill-runner-daemon_3/SNAPSHOT/mill-runner-daemon_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-runner-eclipse_3/SNAPSHOT/mill-runner-eclipse_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-runner-idea_3/SNAPSHOT/mill-runner-idea_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-runner-bsp_3/SNAPSHOT/mill-runner-bsp_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-runner-bsp-worker_3/SNAPSHOT/mill-runner-bsp-worker_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-core-eval_3/SNAPSHOT/mill-core-eval_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-runner-server_3/SNAPSHOT/mill-runner-server_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-runner-launcher_3/SNAPSHOT/mill-runner-launcher_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-runner-meta_3/SNAPSHOT/mill-runner-meta_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-script_3/SNAPSHOT/mill-libs-script_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-init_3/SNAPSHOT/mill-libs-init_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-util_3/SNAPSHOT/mill-libs-util_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-core-internal_3/SNAPSHOT/mill-core-internal_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-core-api_3/SNAPSHOT/mill-core-api_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-core-exec_3/SNAPSHOT/mill-core-exec_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-core-resolve_3/SNAPSHOT/mill-core-resolve_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-core-api-daemon_3/SNAPSHOT/mill-core-api-daemon_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-daemon-server_3/SNAPSHOT/mill-libs-daemon-server_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-rpc_3/SNAPSHOT/mill-libs-rpc_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-runner-codesig_3/SNAPSHOT/mill-runner-codesig_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-scalalib_3/SNAPSHOT/mill-libs-scalalib_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-kotlinlib_3/SNAPSHOT/mill-libs-kotlinlib_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-groovylib_3/SNAPSHOT/mill-libs-groovylib_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-core-internal-cli_3/SNAPSHOT/mill-core-internal-cli_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-core-constants/SNAPSHOT/mill-core-constants-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-daemon-client_3/SNAPSHOT/mill-libs-daemon-client_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-core-api-java11_3/SNAPSHOT/mill-core-api-java11_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-javalib_3/SNAPSHOT/mill-libs-javalib_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-javalib-testrunner_3/SNAPSHOT/mill-libs-javalib-testrunner_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-kotlinlib-api_3/SNAPSHOT/mill-libs-kotlinlib-api_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-kotlinlib-ksp2-api_3/SNAPSHOT/mill-libs-kotlinlib-ksp2-api_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-groovylib-api_3/SNAPSHOT/mill-libs-groovylib-api_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-javalib-api_3/SNAPSHOT/mill-libs-javalib-api_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-javalib-testrunner-entrypoint/SNAPSHOT/mill-libs-javalib-testrunner-entrypoint-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-util-java11_3/SNAPSHOT/mill-libs-util-java11_3-SNAPSHOT.jar"
        )
      )

      val metaClasspath = eval(("--meta-level", "1", "show", "compileClasspath"))
      assert(metaClasspath.isSuccess)
      assertGoldenLiteral(
        upickle.read[Seq[String]](metaClasspath.out)
          .collect { case s"$prefix/custom-local-repo/$rest" => rest },
        List(
          "com/lihaoyi/mill-libs_3/SNAPSHOT/mill-libs_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-runner-autooverride-api_3/SNAPSHOT/mill-runner-autooverride-api_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-kotlinlib_3/SNAPSHOT/mill-libs-kotlinlib_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-groovylib_3/SNAPSHOT/mill-libs-groovylib_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-androidlib_3/SNAPSHOT/mill-libs-androidlib_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-scalajslib_3/SNAPSHOT/mill-libs-scalajslib_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-scalanativelib_3/SNAPSHOT/mill-libs-scalanativelib_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-javascriptlib_3/SNAPSHOT/mill-libs-javascriptlib_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-pythonlib_3/SNAPSHOT/mill-libs-pythonlib_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-util_3/SNAPSHOT/mill-libs-util_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-script_3/SNAPSHOT/mill-libs-script_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-javalib_3/SNAPSHOT/mill-libs-javalib_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-javalib-testrunner_3/SNAPSHOT/mill-libs-javalib-testrunner_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-kotlinlib-api_3/SNAPSHOT/mill-libs-kotlinlib-api_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-kotlinlib-ksp2-api_3/SNAPSHOT/mill-libs-kotlinlib-ksp2-api_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-groovylib-api_3/SNAPSHOT/mill-libs-groovylib-api_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-androidlib-databinding_3/SNAPSHOT/mill-libs-androidlib-databinding_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-scalalib_3/SNAPSHOT/mill-libs-scalalib_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-scalajslib-api_3/SNAPSHOT/mill-libs-scalajslib-api_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-scalanativelib-api_3/SNAPSHOT/mill-libs-scalanativelib-api_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-core-api_3/SNAPSHOT/mill-core-api_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-rpc_3/SNAPSHOT/mill-libs-rpc_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-javalib-api_3/SNAPSHOT/mill-libs-javalib-api_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-javalib-testrunner-entrypoint/SNAPSHOT/mill-libs-javalib-testrunner-entrypoint-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-util-java11_3/SNAPSHOT/mill-libs-util-java11_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-core-api-java11_3/SNAPSHOT/mill-core-api-java11_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-core-api-daemon_3/SNAPSHOT/mill-core-api-daemon_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-daemon-server_3/SNAPSHOT/mill-libs-daemon-server_3-SNAPSHOT.jar",
          "com/lihaoyi/mill-core-constants/SNAPSHOT/mill-core-constants-SNAPSHOT.jar",
          "com/lihaoyi/mill-libs-daemon-client_3/SNAPSHOT/mill-libs-daemon-client_3-SNAPSHOT.jar"
        )
      )

      val fooClasspath = eval(("show", "foo.compileClasspath"))
      assert(fooClasspath.isSuccess)
      assertGoldenLiteral(
        upickle.read[Seq[String]](fooClasspath.out)
          .collect { case s"$prefix/custom-local-repo/$rest" => rest },
        List("com/lihaoyi/mill-core-constants/SNAPSHOT/mill-core-constants-SNAPSHOT.jar")
      )
    }
  }
}
