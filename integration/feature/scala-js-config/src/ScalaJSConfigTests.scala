package mill.integration

import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import utest.*

object ScalaJSConfigTests extends UtestIntegrationTestSuite {

  // Seems this messes with the Scala.js version, and makes former version
  // tests wrongly green sometimes.
  override protected def allowSharedOutputDir = false

  private def check(tester: IntegrationTester, scalaJsVersion: String): Unit = {
    import tester.*

    val env = Map("TEST_SCALA_JS_VERSION" -> scalaJsVersion)

    val out = eval(("run"), env = env, check = true, stdin = os.Inherit).out
    val expected =
      """{
        |  "a": 2,
        |  "b": true
        |}""".stripMargin
    assert(out.endsWith(expected))

    val output = {
      val fastLinkDir = workspacePath / "out/fastLinkJS.dest"
      os.list(fastLinkDir).map(_.subRelativeTo(fastLinkDir))
    }
    val expectedOutput = Seq[os.SubPath]("main.js", "main.js.map")

    assert(output == expectedOutput)

    eval(("test.testForked"), env = env, check = true, stdin = os.Inherit)
  }

  val tests: Tests = Tests {
    test("current") - integrationTest { tester =>
      val current = sys.env.getOrElse(
        "TEST_SCALA_JS_CURRENT_VERSION",
        sys.error("TEST_SCALA_JS_CURRENT_VERSION not set")
      )
      check(tester, current)
    }
    test("former") {
      test - integrationTest { tester =>
        check(tester, "1.18.0")
      }
      test - integrationTest { tester =>
        check(tester, "1.15.0")
      }
      test - integrationTest { tester =>
        check(tester, "1.13.2")
      }
    }
  }
}
