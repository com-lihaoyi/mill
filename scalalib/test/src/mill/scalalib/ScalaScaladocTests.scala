package mill.scalalib

import mill._
import mill.api.Result
import mill.testkit.{TestBaseModule, UnitTester}
import utest._
import HelloWorldTests._
object ScalaScaladocTests extends TestSuite {

  object HelloWorldWithDocVersion extends TestBaseModule {
    object core extends HelloWorldModule {
      override def scalacOptions = T(Seq("-Ywarn-unused", "-Xfatal-warnings"))
      override def scalaDocOptions = super.scalaDocOptions() ++ Seq("-doc-version", "1.2.3")
    }
  }

  object HelloWorldOnlyDocVersion extends TestBaseModule {
    object core extends HelloWorldModule {
      override def scalacOptions = T(Seq("-Ywarn-unused", "-Xfatal-warnings"))
      override def scalaDocOptions = T(Seq("-doc-version", "1.2.3"))
    }
  }

  object HelloWorldDocTitle extends TestBaseModule {
    object core extends HelloWorldModule {
      override def scalaDocOptions = T(Seq("-doc-title", "Hello World"))
    }
  }

  def tests: Tests = Tests {

    test("scalaDocOptions") {
      test("emptyByDefault") - UnitTester(HelloWorldTests.HelloWorld, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldTests.HelloWorld.core.scalaDocOptions)
        assert(
          result.value.isEmpty,
          result.evalCount > 0
        )
      }
      test("override") - UnitTester(HelloWorldDocTitle, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldDocTitle.core.scalaDocOptions)
        assert(
          result.value == Seq("-doc-title", "Hello World"),
          result.evalCount > 0
        )
      }
      test("extend") - UnitTester(HelloWorldWithDocVersion, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldWithDocVersion.core.scalaDocOptions)
        assert(
          result.value == Seq("-Ywarn-unused", "-Xfatal-warnings", "-doc-version", "1.2.3"),
          result.evalCount > 0
        )
      }
      // make sure options are passed during ScalaDoc generation
      test("docJarWithTitle") - UnitTester(
        HelloWorldDocTitle,
        sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world"
      ).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldDocTitle.core.docJar)
        assert(
          result.evalCount > 0,
          os.read(eval.outPath / "core/docJar.dest/javadoc/index.html").contains(
            "<span id=\"doc-title\">Hello World"
          )
        )
      }
      test("docJarWithVersion") - UnitTester(
        HelloWorldWithDocVersion,
        sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world"
      ).scoped { eval =>
        // scaladoc generation fails because of "-Xfatal-warnings" flag
        val Left(Result.Failure(_, None)) = eval.apply(HelloWorldWithDocVersion.core.docJar)
      }
      test("docJarOnlyVersion") - UnitTester(
        HelloWorldOnlyDocVersion,
        sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world"
      ).scoped { eval =>
        // `docJar` requires the `compile` task to succeed (since the addition of Scaladoc 3)
        val Left(Result.Failure(_, None)) = eval.apply(HelloWorldOnlyDocVersion.core.docJar)
      }
    }

  }
}
