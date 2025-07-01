package mill.javascriptlib

import mill.*
import mill.api.Discover
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}

object HelloWorldTests extends TestSuite {

  object HelloWorldJavascript extends TestRootModule {
    object foo extends TypeScriptModule {
      object bar extends TypeScriptModule {}

      override def moduleDeps: Seq[TypeScriptModule] = Seq(bar)

    }

    object qux extends TypeScriptModule {
      override def moduleDeps: Seq[TypeScriptModule] = Seq(foo, foo.bar)
    }

    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world-typescript"

  def tests: Tests = Tests {
    test("run") {
      val baos = new ByteArrayOutputStream()
      UnitTester(HelloWorldJavascript, resourcePath, outStream = new PrintStream(baos)).scoped {
        eval =>

          val Right(_) = eval.apply(HelloWorldJavascript.qux.run(Args("James"))): @unchecked

          assert(baos.toString() == "Hello James Qux\n")
      }
    }
  }
}
