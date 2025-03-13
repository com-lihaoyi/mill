package mill.integration

import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import utest.*

abstract class MillInitSbtTests extends UtestIntegrationTestSuite {

  def initCmd(args: String*): Seq[String] =
    // TODO why is version required (on my machine only?)?
    "init" +: "--sbt-version" +: "1.10.10" +: "--verbose" +: args

  def integrationTestGitRepo[T](
      url: String,
      branch: String
  )(f: IntegrationTester => T) = {
    if (sys.env.contains("CI")) None // TODO setup CI
    else Some(super.integrationTest { tester =>
      val cwd = tester.workspacePath
      val stderr = os.Pipe
      os.proc("git", "init", ".").call(cwd = cwd, stderr = stderr)
      os.proc("git", "remote", "add", "-f", "origin", url).call(cwd = cwd, stderr = stderr)
      os.proc("git", "checkout", branch).call(cwd = cwd, stderr = stderr)
      f(tester)
    })
  }
}

object MillInitSbtAirstreamTests extends MillInitSbtTests {

  def tests: Tests = Tests {
    // single Scala JS module
    // custom repos
    test - integrationTestGitRepo("https://github.com/raquo/Airstream", "tags/v17.2.0") {
      tester =>
        import tester.*

        val initRes = eval(initCmd(), stdout = os.Inherit, stderr = os.Inherit)
        assert(initRes.isSuccess)

        val compileRes = eval("__.compile", stdout = os.Inherit, stderr = os.Inherit)
        assert(compileRes.isSuccess) // TODO check out

        val testRes = eval("__.test", stdout = os.Inherit, stderr = os.Inherit)
        assert(testRes.isSuccess) // TODO check out

        val publishLocalRes = eval("__.publishLocal", stdout = os.Inherit, stderr = os.Inherit)
        assert(!publishLocalRes.isSuccess) // undefined: JsPromiseSignal.this.promise.then
    }
  }
}

object MillInitSbtFs2Tests extends MillInitSbtTests {

  def tests: Tests = Tests {
    // Full layout cross platform
    // cross version specific sources
    // non-cross platform project ("benchmark")
    test - integrationTestGitRepo("https://github.com/typelevel/fs2", "tags/v3.11.0") { tester =>
      import tester.*

      val initRes = eval(initCmd(), stdout = os.Inherit, stderr = os.Inherit)
      assert(initRes.isSuccess)

      // TODO resolve cross deps
      val compileRes = eval("__.compile", stdout = os.Inherit, stderr = os.Inherit)
      assert(!compileRes.isSuccess) // TODO check out
    }
  }
}
