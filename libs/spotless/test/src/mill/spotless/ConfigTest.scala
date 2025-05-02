package mill.spotless

import com.diffplug.spotless.LintState
import utest.*

import java.io.File
import scala.util.Using

object ConfigTest extends TestSuite {

  def tests = Tests {
    val resources = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
    val provisioner = new CoursierProvisioner()
    val resolver = PathResolver(resources)

    def fmt(configFile: os.RelPath, srcFile: File) = {
      val config = Using(
        os.read.inputStream(resources / configFile)
      )(upickle.default.read[SpotlessConfig](_)).get

      given SpotlessContext = SpotlessContext(config.formatter.encoding, provisioner, resolver)

      val steps = config.steps.map(_.build)
      val formatter =
        config.formatter.build(steps, resources.toIO, Seq(srcFile))
      LintState.of(formatter, srcFile)
    }

    test("java steps can be built and applied") {
      val file = (resources / "A.java").toIO
      val lintState = fmt("spotless-config-java.json", file)
      assert(!lintState.isClean)
    }
    test("kotlin steps can be built and applied") {
      val file = (resources / "B.kt").toIO
      val lintState = fmt("spotless-config-kotlin.json", file)
      assert(!lintState.isClean)
    }
    test("scala steps can be built and applied") {
      val file = (resources / "C.scala").toIO
      val lintState = fmt("spotless-config-scala.json", file)
      assert(!lintState.isClean)
    }
  }
}
