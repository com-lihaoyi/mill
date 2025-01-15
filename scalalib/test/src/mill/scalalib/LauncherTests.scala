package mill.scalalib

import mill.testkit.{TestBaseModule, UnitTester}
import utest._

object LauncherTests extends TestSuite {

  val customJavaVersion = "19.0.2"
  object HelloJava extends TestBaseModule with JavaModule {
    object ZincWorkerJava extends ZincWorkerModule {
      def jvmId = s"temurin:$customJavaVersion"
    }
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "launcher"

  def tests: Tests = Tests {
    def check(executable: mill.define.Target[mill.api.PathRef]) = {
      val eval = UnitTester(HelloJava, resourcePath)

      val Right(result1) = eval.apply(executable)

      val text = os.call(result1.value.path).out.text()
      assert(text.contains("test.property null"))
      assert(text.contains("java.home"))
      assert(!text.contains(customJavaVersion))

      val text2 =
        os.call(result1.value.path, env = Map("JAVA_OPTS" -> "-Dtest.property=123")).out.text()
      assert(text2.contains("test.property 123"))
      assert(!text2.contains(customJavaVersion))
      val Right(javaHome) = eval.apply(HelloJava.ZincWorkerJava.javaHome)

      val text3 = os.call(
        result1.value.path,
        env = Map("JAVA_HOME" -> javaHome.value.get.path.toString)
      ).out.text()
      assert(text3.contains("java.home"))
      assert(text3.contains(customJavaVersion))
    }

    test("launcher") - check(HelloJava.launcher)
    test("assembly") - check(HelloJava.assembly)
  }
}
