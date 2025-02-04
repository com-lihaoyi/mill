package mill.scalalib

import mill.api.Result
import mill.testkit.{TestBaseModule, UnitTester}
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}
import HelloWorldTests.*
import mill.define.Discover
import mill.main.TokenReaders._
object ScalaColorOutputTests extends TestSuite {

  object HelloWorldColorOutput extends TestBaseModule {
    object core extends ScalaModule {
      def scalaVersion = scala213Version

      override def scalacOptions = super.scalacOptions() ++ Seq(
        "-Vimplicits"
      )
    }
    lazy val millDiscover: Discover = Discover[this.type]
  }

  def tests: Tests = Tests {
    test("color-output") {

      val errStream = new ByteArrayOutputStream()

      UnitTester(
        HelloWorldColorOutput,
        sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world-color-output",
        errStream = new PrintStream(errStream, true)
      ).scoped { eval =>
        val Left(Result.Failure("Compilation failed", _)) =
          eval.apply(HelloWorldColorOutput.core.compile)
        val output = errStream.toString
        assert(output.contains(s"${Console.RED}!${Console.RESET}${Console.BLUE}I"))
        assert(output.contains(
          s"${Console.GREEN}example.Show[scala.Option[java.lang.String]]${Console.RESET}"
        ))
      }
    }
  }
}
