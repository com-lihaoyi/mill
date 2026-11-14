package mill.scalajslib

import mill.*
import mill.api.{Discover, ExecResult}
import mill.scalalib.TestModule
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

object ClientEnvTests extends TestSuite {
  val scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
  val scalaJSVersion = sys.props.getOrElse("TEST_SCALAJS_VERSION", ???)
  val testUtestVersion = sys.props.getOrElse("TEST_UTEST_VERSION", ???)

  trait ClientEnvModule extends ScalaJSModule {
    def scalaVersion = ClientEnvTests.scalaVersion
    def scalaJSVersion = ClientEnvTests.scalaJSVersion
    override def mainClass = Some("Main")
    object test extends ScalaJSTests with TestModule.Utest {
      override def utestVersion = testUtestVersion
      override def propagateEnv = Task { ClientEnvModule.this.propagateEnv() }
    }
  }

  object ClientEnv extends TestRootModule {
    object propagated extends ClientEnvModule
    object notPropagated extends ClientEnvModule {
      override def propagateEnv = false
    }

    override lazy val millDiscover = {
      import mill.util.TokenReaders.given
      Discover[this.type]
    }
  }

  val millSourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "client-env"
  val clientEnv = sys.env + ("MILL_TEST_CLIENT_ENV" -> "from-client")

  def tests: Tests = Tests {
    // The variable is only set in the environment of the Mill client, not in
    // the one of the process running the tests
    assert(!sys.env.contains("MILL_TEST_CLIENT_ENV"))

    def succeeds(task: Task.Named[?]): Boolean =
      UnitTester(ClientEnv, millSourcePath, env = clientEnv).scoped { eval =>
        eval(task).isRight
      }

    test("run") {
      test("propagated") - assert(succeeds(ClientEnv.propagated.run()))
      test("notPropagated") - assert(!succeeds(ClientEnv.notPropagated.run()))
    }
    test("test") {
      test("propagated") - assert(succeeds(ClientEnv.propagated.test.testForked()))
      test("notPropagated") - assert(!succeeds(ClientEnv.notPropagated.test.testForked()))
    }
  }
}
