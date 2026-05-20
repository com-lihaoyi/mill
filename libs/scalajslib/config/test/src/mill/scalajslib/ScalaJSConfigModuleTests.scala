package mill.scalajslib.config

import mill.*
import mill.api.{Discover, ExecutionPaths}
import mill.scalalib.{ScalaModule, TestModule}
import mill.testkit.{TestRootModule, UnitTester}
import mill.scalalib.DepSyntax
import utest.*

object ScalaJSConfigModuleTests extends TestSuite {
  object Module extends TestRootModule with ScalaModule with ScalaJSConfigModule {
    def scalaVersion = "3.3.1"
    def mvnDeps = Seq(
      mvn"com.lihaoyi::upickle::4.4.1"
    )
    override def mainClass = Some("thing.Thing")
    override def scalaJSSourceMap = false
    override def scalaJSConfig = Task.Anon {
      super.scalaJSConfig()
        .withCheckIR(true)
        .withSourceMap(true)
    }
    object test extends ScalaJSConfigTests with TestModule.Utest {
      def utestVersion = "0.8.3"
    }
    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "scala-js-config"

  val tests: Tests = Tests {
    test("current") - UnitTester(Module, resourcePath).scoped { eval =>
      val Right(runResult) = eval(Module.run()).runtimeChecked
      assert(runResult.evalCount > 0)

      val runPaths = ExecutionPaths.resolve(eval.outPath, Module.run())
      val runLog = os.read(runPaths.log)
      val expected =
        """{
          |  "a": 2,
          |  "b": true
          |}""".stripMargin
      assert(runLog.contains(expected))

      val Right(linkResult) = eval(Module.fastLinkJS).runtimeChecked
      val output =
        os.list(linkResult.value.dest.path).map(_.subRelativeTo(linkResult.value.dest.path)).sorted
      assert(output == Seq[os.SubPath]("main.js", "main.js.map"))

      val Right(testResult) = eval(Module.test.testForked()).runtimeChecked
      assert(testResult.evalCount > 0)
    }
  }
}
