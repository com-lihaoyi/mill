package mill.scalalib

import mill.api.{Discover, ExecResult}
import mill.scalalib.HelloWorldTests.*
import mill.testkit.{TestRootModule, UnitTester}
import mill.util.TokenReaders.*
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}

object ScalaColorOutputTests extends TestSuite {

  object HelloWorldColorOutput extends TestRootModule {
    object core extends ScalaModule {
      def scalaVersion = scala213Version

      override def scalacOptions = super.scalacOptions() ++ Seq(
        "-Vimplicits"
      )
    }
    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {
    test("color-output") {

      val errStream = new ByteArrayOutputStream()

      UnitTester(
        HelloWorldColorOutput,
        sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world-color-output",
        errStream = new PrintStream(errStream, true)
      ).scoped { eval =>
        val Left(ExecResult.Failure("Compilation failed")) =
          eval.apply(HelloWorldColorOutput.core.compile): @unchecked
        val output = errStream.toString
        assert(output.contains(s"${Console.RED}!${Console.RESET}${Console.BLUE}I"))
        assert(output.contains(
          s"${Console.GREEN}example.Show[scala.Option[java.lang.String]]${Console.RESET}"
        ))
      }
    }
  }
}
