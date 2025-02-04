package mill.scalalib

import mill.*
import mill.define.Discover
import utest.*
import utest.framework.TestPath
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule

object DottyDocTests extends TestSuite {
  // a project with static docs
  object StaticDocsModule extends TestBaseModule {
    object static extends ScalaModule {
      def scalaVersion = "0.24.0-RC1"
    }
    lazy val millDiscover = Discover[this.type]
  }

  // a project without static docs (i.e. only api docs, no markdown files)
  object EmptyDocsModule extends TestBaseModule {
    object empty extends ScalaModule {
      def scalaVersion = "0.24.0-RC1"
    }
    lazy val millDiscover = Discover[this.type]
  }

  // a project with multiple static doc folders
  object MultiDocsModule extends TestBaseModule {
    object multidocs extends ScalaModule {
      def scalaVersion = "0.24.0-RC1"
      def docResources = Task.Sources(
        millSourcePath / "docs1",
        millSourcePath / "docs2"
      )
    }
    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "dottydoc"

  def tests: Tests = Tests {
    test("static") - UnitTester(StaticDocsModule, resourcePath).scoped { eval =>
      val Right(_) = eval.apply(StaticDocsModule.static.docJar)
      val dest = eval.outPath / "static/docJar.dest"
      assert(
        os.exists(dest / "out.jar"), // final jar should exist
        // check if extra markdown files have been included and translated to html
        os.exists(dest / "javadoc/_site/index.html"),
        os.exists(dest / "javadoc/_site/nested/extra.html"),
        // also check that API docs have been generated
        os.exists(dest / "javadoc/_site/api/pkg/SomeClass.html")
      )
    }
    test("empty") - UnitTester(EmptyDocsModule, resourcePath).scoped { eval =>
      val Right(_) = eval.apply(EmptyDocsModule.empty.docJar)
      val dest = eval.outPath / "empty/docJar.dest"
      assert(
        os.exists(dest / "out.jar"),
        os.exists(dest / "javadoc/_site/api/pkg/SomeClass.html")
      )
    }
    test("multiple") - UnitTester(MultiDocsModule, resourcePath).scoped { eval =>
      val Right(_) = eval.apply(MultiDocsModule.multidocs.docJar)
      val dest = eval.outPath / "multidocs/docJar.dest"
      assert(
        os.exists(dest / "out.jar"), // final jar should exist
        os.exists(dest / "javadoc/_site/api/pkg/SomeClass.html"),
        os.exists(dest / "javadoc/_site/index.html"),
        os.exists(dest / "javadoc/_site/nested/original.html"),
        os.exists(dest / "javadoc/_site/nested/extra.html"),
        // check that later doc sources overwrite earlier ones
        os.read(dest / "javadoc/_site/index.html").contains("overwritten")
      )
    }
  }

}
