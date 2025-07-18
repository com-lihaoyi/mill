package mill.scalalib

import mill.*
import mill.api.Discover
import utest.*
import mill.testkit.UnitTester
import mill.testkit.TestRootModule

object DottyDocTests extends TestSuite {
  // a project with static docs
  object StaticDocsModule extends TestRootModule {
    object static extends ScalaModule {
      def scalaVersion = "0.24.0-RC1"
    }
    lazy val millDiscover = Discover[this.type]
  }

  // a project without static docs (i.e. only api docs, no markdown files)
  object EmptyDocsModule extends TestRootModule {
    object empty extends ScalaModule {
      def scalaVersion = "0.24.0-RC1"
    }
    lazy val millDiscover = Discover[this.type]
  }

  // a project with multiple static doc folders
  object MultiDocsModule extends TestRootModule {
    object multidocs extends ScalaModule {
      def scalaVersion = "0.24.0-RC1"
      def docResources = Task.Sources(
        moduleDir / "docs1",
        moduleDir / "docs2"
      )
    }
    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "dottydoc"

  def tests: Tests = Tests {
    test("static") - UnitTester(StaticDocsModule, resourcePath).scoped { eval =>
      val Right(_) = eval.apply(StaticDocsModule.static.docJar): @unchecked
      val scaladoc = eval.outPath / "static/scalaDocGenerated.dest"
      val docJar = eval.outPath / "static/docJar.dest"
      assert(
        // check if extra markdown files have been included and translated to html
        os.exists(scaladoc / "javadoc/_site/index.html"),
        os.exists(scaladoc / "javadoc/_site/nested/extra.html"),
        // also check that API docs have been generated
        os.exists(scaladoc / "javadoc/_site/api/pkg/SomeClass.html"),
        // final jar should exist
        os.exists(docJar / "out.jar")
      )
    }
    test("empty") - UnitTester(EmptyDocsModule, resourcePath).scoped { eval =>
      val Right(_) = eval.apply(EmptyDocsModule.empty.docJar): @unchecked
      val scaladoc = eval.outPath / "empty/scalaDocGenerated.dest"
      val docJar = eval.outPath / "empty/docJar.dest"
      assert(
        os.exists(scaladoc / "javadoc/_site/api/pkg/SomeClass.html"),
        os.exists(docJar / "out.jar")
      )
    }
    test("multiple") - UnitTester(MultiDocsModule, resourcePath).scoped { eval =>
      val Right(_) = eval.apply(MultiDocsModule.multidocs.docJar): @unchecked
      val scaladoc = eval.outPath / "multidocs/scalaDocGenerated.dest"
      val docJar = eval.outPath / "multidocs/docJar.dest"
      assert(
        os.exists(docJar / "out.jar"), // final jar should exist
        os.exists(scaladoc / "javadoc/_site/api/pkg/SomeClass.html"),
        os.exists(scaladoc / "javadoc/_site/index.html"),
        os.exists(scaladoc / "javadoc/_site/nested/original.html"),
        os.exists(scaladoc / "javadoc/_site/nested/extra.html"),
        // check that later doc sources overwrite earlier ones
        os.read(scaladoc / "javadoc/_site/index.html").contains("overwritten")
      )
    }
  }

}
