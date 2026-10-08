package mill.scalajslib.config

import mill.*
import mill.api.Discover
import mill.scalalib.{ScalaModule, TestModule}
import mill.testkit.{TestRootModule, UnitTester}
import utest.*
import mill.api.Task.fail
import org.scalajs.linker.interface.OutputDirectory

object InMemTests extends TestSuite {
  trait HelloJSWorldModule extends ScalaModule with ScalaJSConfigModule
      with Cross.Module2[String, Boolean] {
    def scalaVersion = crossValue
    def isFullOpt = crossValue2
    val mem = MemOutputDirectory()
    override def customLinkerOutputDir: Option[MemOutputDirectory] = Some(mem)
    def scalaJSConfig =
      if (isFullOpt)
        Task.Anon {
          super.scalaJSConfig().fullOptimized
        }
      else
        super.scalaJSConfig
  }

  object HelloJSWorld extends TestRootModule {
    val scalaVersions = Seq("2.13.18", "3.7.4")
    val matrix = for (sv <- scalaVersions; fullOpt <- Seq(false, true)) yield (sv, fullOpt)
    val utestVersion = "0.8.9"

    object build extends Cross[RootModule](matrix)
    trait RootModule extends HelloJSWorldModule {
      def sources =
        if (isFullOpt) Task.Sources("full")
        else Task.Sources("fast")
      object test extends ScalaJSConfigTests with TestModule.Utest {
        def sources =
          if (isFullOpt) Task.Sources("full")
          else Task.Sources("fast")
        override def utestVersion = HelloJSWorld.utestVersion
      }
    }

    override lazy val millDiscover = {
      import mill.util.TokenReaders.given
      Discover[this.type]
    }
  }

  val millSourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-js-world"

  def testAllMatrix(f: String => Unit): Unit =
    for (scala <- HelloJSWorld.scalaVersions)
      f(scala)

  def tests: Tests = Tests {

    test("linking") {

      test("fast") {
        testAllMatrix { scalaVersion =>
          UnitTester(HelloJSWorld, millSourcePath).scoped { eval =>
            val res = eval {
              HelloJSWorld.build(scalaVersion, false).fastLinkJS
            }
            assert(res.isRight)
            // As the files are not actually written to disk, this folder should be empty...
            assert(os.list(res.right.get.value.dest.path).isEmpty)
            // but the in memory filesystem, should not be.
            val inMemFiles =
              HelloJSWorld.build(scalaVersion, false).customLinkerOutputDir.get.fileNames()
            assert(!inMemFiles.isEmpty)

          }
        }
      }

      test("full") {
        testAllMatrix { scalaVersion =>
          UnitTester(HelloJSWorld, millSourcePath).scoped { eval =>
            val res = eval {
              HelloJSWorld.build(scalaVersion, true).fullLinkJS
            }
            assert(res.isRight)
            assert(os.list(res.right.get.value.dest.path).isEmpty)
            val inMemFiles =
              HelloJSWorld.build(scalaVersion, false).customLinkerOutputDir.get.fileNames()
            assert(!inMemFiles.isEmpty)
          }
        }
      }

    }
  }
}
