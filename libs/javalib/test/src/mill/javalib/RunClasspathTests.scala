package mill.javalib

import mill.api.{Discover, Task}
import mill.testkit.{TestRootModule, UnitTester}
import mill.util.TokenReaders.*

import utest.*

import java.net.URI
import java.nio.file.Paths
import java.io.ByteArrayOutputStream
import java.io.PrintStream

object RunClasspathTests extends TestSuite {

  object TestCase extends TestRootModule {
    object lib extends JavaModule

    object app extends JavaModule {
      def moduleDeps = Seq(lib)
      def mainClass = Some("app.MyApp")
    }

    object appAsJars extends JavaModule {
      def moduleDeps = Seq(app)
      def mainClass = app.mainClass
      def runClasspath = runClasspathAsJars
    }

    lazy val millDiscover = Discover[this.type]
  }

  val tests: Tests = Tests {
    test("test") {
      val sources = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "run"

      def locationFromOutput(kind: String, out: String): os.SubPath = {
        val uriStr =
          out.linesIterator.find(_.startsWith(s"$kind URI: ")).get.stripPrefix(s"$kind URI: ")
        val path = os.Path(Paths.get(new URI(uriStr)))
        path.subRelativeTo(TestCase.moduleDir)
      }

      val mainBaos = new ByteArrayOutputStream
      val jarBaos = new ByteArrayOutputStream

      UnitTester(
        TestCase,
        sourceRoot = sources,
        outStream = new PrintStream(mainBaos, true)
      ).scoped { eval =>
        eval(TestCase.app.run()).left.map(f => throw f.exception)
      }
      UnitTester(
        TestCase,
        sourceRoot = sources,
        outStream = new PrintStream(jarBaos, true)
      ).scoped { eval =>
        eval(TestCase.appAsJars.run()).left.map(f => throw f.exception)
      }

      val mainOut = new String(mainBaos.toByteArray)
      val jarOut = new String(jarBaos.toByteArray)

      val appDir = locationFromOutput("App", mainOut)
      val appJar = locationFromOutput("App", jarOut)
      val libDir = locationFromOutput("Lib", mainOut)
      val libJar = locationFromOutput("Lib", jarOut)

      val expectedAppDir = os.sub / "out/app/compile.dest/classes"
      val expectedAppJar = os.sub / "out/app/jar.dest/out.jar"
      val expectedLibDir = os.sub / "out/lib/compile.dest/classes"
      val expectedLibJar = os.sub / "out/lib/jar.dest/out.jar"

      assert(expectedAppDir == appDir)
      assert(expectedAppJar == appJar)
      assert(expectedLibDir == libDir)
      assert(expectedLibJar == libJar)
    }
  }
}
