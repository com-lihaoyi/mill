package mill.scalalib

import mill.api.{Discover, Task}
import mill.api.daemon.ExecResult
import mill.scalalib.HelloWorldTests.scala33Version
import mill.testkit.{TestRootModule, UnitTester}
import mill.util.TokenReaders.*
import utest.*

object ScalaCompilerBridgeTests extends TestSuite {

  val sourcesPath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "compiler-bridge/src"

  object Hello extends TestRootModule {

    object default extends ScalaModule {
      def scalaVersion = scala33Version
      def sources = Task.Sources(sourcesPath)
    }
    object wrong extends ScalaModule {
      def scalaVersion = scala33Version
      def sources = Task.Sources(sourcesPath)

      def scalaCompilerBridge = Task {
        val cp = defaultResolver()
          .classpath(Seq(mvn"org.scala-lang:scala3-library_3:$scala33Version"))
          .filter(_.path.last.startsWith("scala3-library_3"))
          .filter(_.path.last.endsWith(".jar"))
        assert(cp.length == 1)
        Some(cp.head)
      }
    }
    object ok extends ScalaModule {
      def scalaVersion = scala33Version
      def sources = Task.Sources(sourcesPath)

      def scalaCompilerBridge = Task {
        val cp =
          defaultResolver().classpath(Seq(mvn"org.scala-lang:scala3-sbt-bridge:$scala33Version"))
        assert(cp.length == 1)
        Some(cp.head)
      }
    }

    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {
    test("simple") - UnitTester(
      Hello,
      sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "compiler-bridge"
    ).scoped { eval =>
      val Right(defaultRes) = eval(Hello.default.compile): @unchecked
      val Left(wrongRes0: ExecResult.Exception) = eval(Hello.wrong.compile): @unchecked
      val Right(okRes) = eval(Hello.ok.compile): @unchecked

      wrongRes0.throwable match {
        case _: ClassNotFoundException =>
        case other =>
          throw Exception(
            "Unexpected exception when passing wrong compiler bridge, expected ClassNotFoundException",
            other
          )
      }

      def classFiles(path: os.Path) =
        os.walk(path)
          .filter(os.isFile)
          .filter(_.last.endsWith(".class"))
          .filter(!_.last.contains("$"))
          .map(_.subRelativeTo(path))

      val defaultClassFiles = classFiles(defaultRes.value.classes.path)
      val okClassFiles = classFiles(okRes.value.classes.path)

      assert(defaultClassFiles == Seq(os.sub / "thing/Foo.class"))
      assert(okClassFiles == Seq(os.sub / "thing/Foo.class"))
    }
  }
}
