package mill.scalalib

import mill.*
import mill.api.Discover
import utest.*
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import scala.util.Properties

object UnidocTests extends TestSuite {
  trait UnidocTestRootModule(scalaVersion: String) extends TestRootModule { self =>
    object foo extends ScalaModule {
      def scalaVersion = self.scalaVersion
    }

    object bar extends ScalaModule {
      def scalaVersion = self.scalaVersion

      override def moduleDeps = Seq(foo)
    }

    object docs extends UnidocModule {
      def scalaVersion = self.scalaVersion

      def unidocDocumentTitle = Task { "MyApp" }

      def unidocSourceUrl = Task { Some(s"https://github.com/test-org/my-app/blob/main") }

      def moduleDeps = Seq(bar)
    }
  }

  object Scala2 extends UnidocTestRootModule("2.13.16") {
    lazy val millDiscover = Discover[this.type]
  }

  object Scala3 extends UnidocTestRootModule("3.7.0") {
    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "unidoc"

  def tests: Tests = Tests {
    def doTest(module: UnidocTestRootModule, site: Boolean, isScala3: Boolean) =
      UnitTester(module, resourcePath).scoped { eval =>
        val task = if (site) module.docs.unidocSite else module.docs.unidocLocal
        val Right(_) = eval.apply(task): @unchecked
        val dest = eval.outPath / "docs" / (if (site) "unidocSite.dest" else "unidocLocal.dest")
        val sandbox = os.pwd

        def fixWindowsPath(path: String) =
          if (Properties.isWin) path.replace("\\", "/")
          else path

        val baseUrl =
          if (site) "https://github.com/test-org/my-app/blob/main"
          else {
            fixWindowsPath(
              if (isScala3) s"file://${module.moduleDir.toString.replace(sandbox.toString, "")}"
              else module.moduleDir.toString
            )
          }
        assertAll(
          // both modules should be present
          os.exists(dest / "foo" / "Foo.html"),
          os.exists(dest / "bar" / "Bar.html"),
          // the source links should point to the correct files
          os.read(dest / "foo" / "Foo.html").contains(s"$baseUrl/foo/src/foo/Foo.scala"),
          os.read(dest / "bar" / "Bar.html").contains(s"$baseUrl/bar/src/bar/Bar.scala")
        )
      }

    test("scala2 site") - doTest(Scala2, site = true, isScala3 = false)
    test("scala2 local") - doTest(Scala2, site = false, isScala3 = false)
    test("scala3 site") - doTest(Scala3, site = true, isScala3 = true)
    test("scala3 local") - doTest(Scala3, site = false, isScala3 = true)
  }
}
