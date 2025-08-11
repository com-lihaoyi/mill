package mill.javalib

import mill.api.{Discover, Task}
import mill.testkit.{TestRootModule, UnitTester}
import mill.util.TokenReaders.*
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}

object JavaHomeTests extends TestSuite {

  object JavaJdk11DoesntCompile extends TestRootModule {

    object javamodule extends JavaModule {
      def jvmId = "temurin:11.0.25"
    }
    object scalamodule extends JavaModule {
      def jvmId = "temurin:11.0.25"
      def scalaVersion = "2.13.14"
    }
    lazy val millDiscover = Discover[this.type]
  }

  object JavaJdk17Compiles extends TestRootModule {
    object javamodule extends JavaModule {
      def jvmId = "temurin:17.0.13"
    }
    object scalamodule extends JavaModule {
      def jvmId = "temurin:17.0.13"

      def scalaVersion = "2.13.14"
    }
    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-java"

  def tests: Tests = Tests {

    test("compileApis") {
      val resourcePathCompile = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "java-scala-11"
      test("jdk11java") {
        val baos = new ByteArrayOutputStream()
        UnitTester(
          JavaJdk11DoesntCompile,
          resourcePathCompile,
          errStream = new PrintStream(baos)
        ).scoped { eval =>
          val Left(_) = eval.apply(JavaJdk11DoesntCompile.javamodule.compile): @unchecked
          assert(baos.toString.contains("cannot find symbol"))
          assert(baos.toString.contains("method indent"))
        }
      }

      test("jdk17java") {
        UnitTester(JavaJdk17Compiles, resourcePathCompile).scoped { eval =>
          val Right(_) = eval.apply(JavaJdk17Compiles.javamodule.compile): @unchecked
        }
      }

      // This doesn't work because Zinc doesn't apply the javaHome config to
      // the Scala compiler JVM, which always runs in-memory https://github.com/sbt/zinc/discussions/1498
//      test("jdk11scala"){
//        val baos = new ByteArrayOutputStream()
//        val eval = UnitTester(JavaJdk11DoesntCompile, resourcePathCompile)
//        val Left(result) = eval.apply(JavaJdk11DoesntCompile.scalamodule.compile)
//      }

      test("jdk17scala") {
        UnitTester(JavaJdk17Compiles, resourcePathCompile).scoped { eval =>
          val Right(_) = eval.apply(JavaJdk17Compiles.scalamodule.compile): @unchecked
        }
      }
    }

  }
}
