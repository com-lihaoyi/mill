package mill.javascriptlib

import mill._
import mill.testkit.{TestBaseModule, UnitTester}
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}

object HelloWorldTests extends TestSuite {

  object HelloWorldJavascript extends TestBaseModule {
    object foo extends TypeScriptModule {
      object bar extends TypeScriptModule {}

      override def moduleDeps: Seq[TypeScriptModule] = Seq(bar)

    }

    object qux extends TypeScriptModule {
      override def moduleDeps: Seq[TypeScriptModule] = Seq(foo, foo.bar)
    }
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world-typescript"

  def tests: Tests = Tests {
    test("run") {
      val baos = new ByteArrayOutputStream()
      val eval = UnitTester(HelloWorldJavascript, resourcePath, outStream = new PrintStream(baos))

      val Right(result) = eval.apply(HelloWorldJavascript.qux.run(Args("James")))

      assert(baos.toString() == "Hello James Qux\n")
    }
  }
}
