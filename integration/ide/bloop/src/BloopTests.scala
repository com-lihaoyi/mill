package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object BloopTests extends UtestIntegrationTestSuite {

  val tests: Tests = Tests {
//    test("test") - {
//
//      test("root module bloop config should be created") - integrationTest { tester =>
//        import tester._
//        val res = eval("mill.contrib.bloop.Bloop/install")
//        assert(res.isSuccess)
//        assert(os.exists(workspacePath / ".bloop/root-module.json"))
//      }
//
//      test("mill-build config should contain build.mill source") - integrationTest { tester =>
//        import tester._
//        val millBuildJsonFile = workspacePath / ".bloop/mill-build-.json"
//        val installResult: Boolean = eval("mill.contrib.bloop.Bloop/install").isSuccess
//        val config = ujson.read(os.read.stream(millBuildJsonFile))
//        assert(installResult)
//        assert(config("project")("sources").arr.exists(path =>
//          os.Path(path.str).last == "build.mill"
//        ))
//
//        if (isPackagedLauncher) {
//          val modules = config("project")("resolution")("modules").arr
//
//          def sourceJars(org: String, namePrefix: String) = modules
//            .filter(mod => mod("organization").str == org && mod("name").str.startsWith(namePrefix))
//            .flatMap(_("artifacts").arr)
//            .filter(_("classifier").strOpt.contains("sources"))
//            .flatMap(_("path").strOpt)
//            .filter(_.endsWith("-sources.jar"))
//            .distinct
//
//          // Look for one of Mill's own source JARs
//          val millScalaLibSourceJars = sourceJars("com.lihaoyi", "mill-scalalib_")
//          assert(millScalaLibSourceJars.nonEmpty)
//          // Look for a source JAR of a dependency
//          val coursierCacheSourceJars = sourceJars("io.get-coursier", "coursier-cache_")
//          assert(coursierCacheSourceJars.nonEmpty)
//        }
//      }
//    }
  }
}
