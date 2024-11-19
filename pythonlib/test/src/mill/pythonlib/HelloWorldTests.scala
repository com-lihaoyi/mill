package mill
package pythonlib

import mill.testkit.{TestBaseModule, UnitTester}
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}

object HelloWorldTests extends TestSuite {

  object HelloWorldPython extends TestBaseModule {
    object foo extends PythonModule {
      override def mainFileName = "foo.py"
      object bar extends PythonModule {
        override def mainFileName = "bar.py"
      }
    }

    object qux extends PythonModule {
      override def mainFileName = "qux.py"
    }
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world-python"

  def tests: Tests = Tests {
    test("run") {
      val baos = new ByteArrayOutputStream()
      val eval = UnitTester(HelloWorldPython, resourcePath, outStream = new PrintStream(baos))

      val Right(result) = eval.apply(HelloWorldPython.qux.run(Args()))

      assert(baos.toString() == "Hello,  Qux!\n")
    }
  }
}
