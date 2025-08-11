package mill
package pythonlib

import mill.api.Discover
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}

object HelloWorldTests extends TestSuite {

  object HelloWorldPython extends TestRootModule {
    object foo extends PythonModule {
      override def moduleDeps: Seq[PythonModule] = Seq(bar)
      object bar extends PythonModule
    }

    object qux extends PythonModule {
      override def moduleDeps: Seq[PythonModule] = Seq(foo)
      override def mainScript = Task.Source("src/qux.py")
      object test extends PythonTests with TestModule.Unittest
    }

    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world-python"
  def tests: Tests = Tests {
    test("run") {
      val baos = new ByteArrayOutputStream()
      UnitTester(HelloWorldPython, resourcePath, outStream = new PrintStream(baos)).scoped { eval =>

        val Right(_) = eval.apply(HelloWorldPython.qux.run(Args())): @unchecked

        assert(baos.toString().contains("Hello,  Qux!\n"))
      }
    }

    test("test") {
      UnitTester(HelloWorldPython, resourcePath).scoped { eval =>

        val result = eval.apply(HelloWorldPython.qux.test.testForked())
        assert(result.isRight)
      }
    }
  }
}
