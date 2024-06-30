package mill.scalalib

import mill.eval.Evaluator
import mill.util.TestEvaluator
import mill.util.TestUtil
import utest._
import utest.framework.TestPath

object CoursierMirrorTests extends TestSuite {

  val resourcePath = os.pwd / "scalalib" / "test" / "resources" / "coursier"

  object CoursierTest extends TestUtil.BaseModule {
    object core extends ScalaModule {
      def scalaVersion = "2.13.12"
    }
  }

  def workspaceTest[T](
      m: TestUtil.BaseModule,
      resourcePath: os.Path = resourcePath,
      env: Map[String, String] = Evaluator.defaultEnv,
      debug: Boolean = false
  )(t: TestEvaluator => T)(implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m, env = env, debugEnabled = debug)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath / os.up)
    os.copy(resourcePath, m.millSourcePath)
    t(eval)
  }

  // The below tests pass individually. Setting up the proxy to an
  // invalid proxy and setting system properties has side effects that
  // will break other tests in the test suite, so I recommend you only
  // uncomment when testing config integration locally.

  def tests: Tests = Tests {
    "readMirror - system prop" - workspaceTest(CoursierTest) { eval =>
      sys.props("coursier.mirrors") = (resourcePath / "mirror.properties").toString()
      val Right((result, evalCount)) = eval.apply(CoursierTest.core.repositoriesTask)
      val centralReplaced = result.exists { repo =>
        repo.repr.contains("https://repo.maven.apache.org/maven2")
      }
      assert(centralReplaced)
    }

    // we can't use sys.props to isolate these. It will use the first
    // value of any prop set in the props in any test. This will
    // persist beyond the lifetime of the test to the other tests in
    // the task run. I believe we would need to fork the jvm for each
    // test in order to achieve isolation, but this would be
    // costly. If anyone has ideas of how to better test these in
    // isolation, please provide a PR.

    // "readMirror - no prop" - workspaceTest(CoursierTest){ eval =>
    //   // sys.props.addOne("scala-cli.config" -> (resourcePath / "empty-coursier-config.json").toString())
    //   val Right((result, evalCount)) = eval.apply(CoursierTest.core.repositoriesTask)
    //   val centralNotReplaced = result.forall { repo =>
    //     !repo.repr.contains("https://repo.maven.apache.org/maven2")
    //   }
    //   assert(centralNotReplaced)
    // }

    // "readMirror - config file" - workspaceTest(CoursierTest) { eval =>
    //   sys.props.addOne("scala-cli.config" -> (resourcePath / "coursier-config.json").toString())
    //   val Right((result, evalCount)) = eval.apply(CoursierTest.core.repositoriesTask)
    //   val resultReprs = result.map(_.repr).mkString("|")
    //   println(resultReprs)
    //   assert(
    //     resultReprs == "MavenRepository(https://somecompany.com/artifactory/someRepo, Some(Authentication(REPO_USER, ****, List(), true, None, false, false)), None, true)|ivy:https://somecompany.com/artifactory/qordoba-releases/[organisation]/[module](_[scalaVersion])(_[sbtVersion])/[revision]/[type]s/[artifact](-[classifier]).[ext]|MavenRepository(https://repo.maven.apache.org/maven2/, None, None, true)"
    //   )
    //   import coursier.proxy.SetupProxy
    //   assert(SetupProxy.setup())
    // }
  }
}
