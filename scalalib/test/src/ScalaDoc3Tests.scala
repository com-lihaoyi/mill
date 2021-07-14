package mill.scalalib

import mill._
import utest._
import utest.framework.TestPath
import mill.util.{TestEvaluator, TestUtil}

import scala.collection.JavaConverters._
import scala.util.Properties.isJavaAtLeast

object ScalaDoc3Tests extends TestSuite {
  trait TestBase extends TestUtil.BaseModule {
    def millSourcePath = TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
  }

  // a project with static docs
  object StaticDocsModule extends TestBase {
    object static extends ScalaModule {
      def scalaVersion = "3.0.0-RC1"
    }
  }

  // a project without static docs (i.e. only api docs, no markdown files)
  object EmptyDocsModule extends TestBase {
    object empty extends ScalaModule {
      def scalaVersion = "3.0.0-RC1"
    }
  }

  // a project with multiple static doc folders
  object MultiDocsModule extends TestBase {
    object multidocs extends ScalaModule {
      def scalaVersion = "3.0.0-RC1"
      def docResources = T.sources(
        millSourcePath / "docs1",
        millSourcePath / "docs2"
      )
    }
  }

  val resourcePath = os.pwd / "scalalib" / "test" / "resources" / "scaladoc3"

  def workspaceTest[T](
      m: TestUtil.BaseModule,
      resourcePath: os.Path = resourcePath
  )(t: TestEvaluator => T)(
      implicit tp: TestPath
  ): T = {
    val eval = new TestEvaluator(m)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath / os.up)
    os.copy(resourcePath, m.millSourcePath)
    t(eval)
  }

  def tests: Tests = Tests {
    "static" - workspaceTest(StaticDocsModule) { eval =>
      val Right((_, _)) = eval.apply(StaticDocsModule.static.docJar)
      val dest = eval.outPath / "static" / "docJar" / "dest"
      assert(
        os.exists(dest / "out.jar"), // final jar should exist
        // check if extra markdown files have been included and translated to html
        os.exists(dest / "javadoc" / "index.html"),
        os.exists(dest / "javadoc" / "nested" / "extra.html"),
        // also check that API docs have been generated
        os.exists(dest / "javadoc" / "api" / "pkg" / "SomeClass.html")
      )
    }
    "empty" - workspaceTest(EmptyDocsModule) { eval =>
      val Right((_, _)) = eval.apply(EmptyDocsModule.empty.docJar)
      val dest = eval.outPath / "empty" / "docJar" / "dest"
      assert(
        os.exists(dest / "out.jar"),
        os.exists(dest / "javadoc" / "api" / "pkg" / "SomeClass.html")
      )
    }
    "multiple" - workspaceTest(MultiDocsModule) { eval =>
      val Right((_, _)) = eval.apply(MultiDocsModule.multidocs.docJar)
      val dest = eval.outPath / "multidocs" / "docJar" / "dest"
      assert(
        os.exists(dest / "out.jar"), // final jar should exist
        os.exists(dest / "javadoc" / "api" / "pkg" / "SomeClass.html"),
        os.exists(dest / "javadoc" / "index.html"),
        os.exists(dest / "javadoc" / "docs" / "nested" / "original.html"),
        os.exists(dest / "javadoc" / "docs" / "nested" / "extra.html"),
        // check that later doc sources overwrite earlier ones
        os.read(dest / "javadoc" / "index.html").contains("overwritten")
      )
    }
  }

}
