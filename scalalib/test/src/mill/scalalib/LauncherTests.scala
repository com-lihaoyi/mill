package mill.scalalib

import mill.define.Discover
import mill.testkit.{TestBaseModule, UnitTester}
import utest.*
import mill.util.TokenReaders.*

object LauncherTests extends TestSuite {

  val customJavaVersion = "19.0.2"
  object HelloJava extends TestBaseModule with JavaModule {
    object JvmWorkerJava extends JvmWorkerModule {
      def jvmId = s"temurin:$customJavaVersion"
    }

    def javacOptions = Seq("-target", "1.8", "-source", "1.8")

    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "launcher"

  def tests: Tests = Tests {
    def check(executableTask: mill.define.Target[mill.api.PathRef], copyBat: Boolean = false) = {
      val eval = UnitTester(HelloJava, resourcePath)

      val Right(result1) = eval.apply(executableTask): @unchecked

      val executable =
        if (mill.constants.Util.isWindows && copyBat) {
          val prev = result1.value.path
          val next = prev / ".." / s"${prev.baseName}.bat"
          os.copy(prev, next)
          next
        } else result1.value.path

      val text = os.call(executable).out.text()
      assert(text.contains("test.property null"))
      assert(text.contains("java.home"))
      assert(!text.contains(customJavaVersion))

      val text2 = os
        .call(executable, env = Map("JAVA_OPTS" -> "-Dtest.property=123"))
        .out.text()
      assert(text2.contains("test.property 123"))
      assert(!text2.contains(customJavaVersion))
      val Right(javaHome) = eval.apply(HelloJava.JvmWorkerJava.javaHome): @unchecked

      val text3 = os
        .call(executable, env = Map("JAVA_HOME" -> javaHome.value.get.path.toString))
        .out.text()
      assert(text3.contains("java.home"))
      assert(text3.contains(customJavaVersion))
    }

    test("launcher") - check(HelloJava.launcher)
    // Windows requires that you copy the `.jar` file to a `.bat` extension before running it
    test("assembly") - check(HelloJava.assembly, copyBat = true)
  }
}
