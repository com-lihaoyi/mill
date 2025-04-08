package mill.scalalib

import mill.define.{Args, Discover, ModuleRef, Task}
import mill.api.ExecResult
import mill.testkit.{TestBaseModule, UnitTester}
import mill.util.TokenReaders.*
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}

object JavaHomeTests extends TestSuite {

  object HelloJavaJavaHome11Override extends TestBaseModule {
    object JvmWorkerJava11 extends JvmWorkerModule {
      def jvmId = "temurin:11.0.24"
    }

    object core extends JavaModule {
      override def jvmWorker = ModuleRef(JvmWorkerJava11)
      override def docJarUseArgsFile = false
      object test extends JavaTests with TestModule.Junit4
    }

    lazy val millDiscover = Discover[this.type]
  }

  object HelloJavaJavaHome17Override extends TestBaseModule {
    object JvmWorkerJava17 extends JvmWorkerModule {
      def jvmId = "temurin:17.0.9"
    }

    object core extends JavaModule {
      override def jvmWorker = ModuleRef(JvmWorkerJava17)
      override def docJarUseArgsFile = false
      object test extends JavaTests with TestModule.Junit4
    }
    lazy val millDiscover = Discover[this.type]
  }

  object JavaJdk11DoesntCompile extends TestBaseModule {
    object JvmWorkerJava extends JvmWorkerModule {
      def jvmId = "temurin:11.0.25"
    }

    object javamodule extends JavaModule {
      override def jvmWorker = ModuleRef(JvmWorkerJava)
    }
    object scalamodule extends JavaModule {
      override def jvmWorker = ModuleRef(JvmWorkerJava)
      def scalaVersion = "2.13.14"
    }
    lazy val millDiscover = Discover[this.type]
  }

  object JavaJdk17Compiles extends TestBaseModule {
    object JvmWorkerJava extends JvmWorkerModule {
      def jvmId = "temurin:17.0.13"
    }

    object javamodule extends JavaModule {
      override def jvmWorker = ModuleRef(JvmWorkerJava)
    }
    object scalamodule extends JavaModule {
      override def jvmWorker = ModuleRef(JvmWorkerJava)

      def scalaVersion = "2.13.14"
    }
    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-java"

  def tests: Tests = Tests {

    test("javaHome") {
      test("compile11") {
        val eval = UnitTester(HelloJavaJavaHome11Override, resourcePath)

        val Right(result) = eval.apply(HelloJavaJavaHome11Override.core.compile): @unchecked

        val coreClassFile = os.walk(result.value.classes.path).find(_.last == "Core.class")

        assert(
          coreClassFile.isDefined,

          // The first eight bytes are magic numbers followed by two bytes for major version and two bytes for minor version
          // We are overriding to java 11 which corresponds to class file version 55
          os.read.bytes(coreClassFile.get, 4, 4).toSeq == Seq[Byte](0, 0, 0, 55)
        )
      }
      test("compile17") {
        val eval = UnitTester(HelloJavaJavaHome17Override, resourcePath)

        val Right(result) = eval.apply(HelloJavaJavaHome17Override.core.compile): @unchecked

        val coreClassFile = os.walk(result.value.classes.path).find(_.last == "Core.class")

        assert(
          coreClassFile.isDefined,

          // The first eight bytes are magic numbers followed by two bytes for major version and two bytes for minor version
          // We are overriding to java 17 which corresponds to class file version 67
          os.read.bytes(coreClassFile.get, 4, 4).toSeq == Seq[Byte](0, 0, 0, 61)
        )
      }
      test("run11") {
        val eval = UnitTester(HelloJavaJavaHome11Override, resourcePath)

        val path = eval.evaluator.workspace / "java.version"
        val Right(_) =
          eval.apply(HelloJavaJavaHome11Override.core.run(Task.Anon(Args(path)))): @unchecked

        assert(
          os.read(path).startsWith("11.")
        )
      }
      test("run17") {
        val eval = UnitTester(HelloJavaJavaHome17Override, resourcePath)

        val path = eval.evaluator.workspace / "java.version"
        val Right(_) =
          eval.apply(HelloJavaJavaHome17Override.core.run(Task.Anon(Args(path)))): @unchecked

        assert(
          os.read(path).startsWith("17.")
        )
      }
      test("test11") {
        val eval = UnitTester(HelloJavaJavaHome11Override, resourcePath)

        val Left(ExecResult.Failure(ref1)) =
          eval.apply(HelloJavaJavaHome11Override.core.test.testForked()): @unchecked

//        assert(
//          v1._2(0).fullyQualifiedName == "hello.MyCoreTests.java11Test",
//          v1._2(0).status == "Success",
//          v1._2(1).fullyQualifiedName == "hello.MyCoreTests.java17Test",
//          v1._2(1).status == "Failure"
//        )
      }
      test("test17") {
        val eval = UnitTester(HelloJavaJavaHome17Override, resourcePath)

        val Left(ExecResult.Failure(ref1)) =
          eval.apply(HelloJavaJavaHome17Override.core.test.testForked()): @unchecked

//        assert(
//          v1._2(0).fullyQualifiedName == "hello.MyCoreTests.java11Test",
//          v1._2(0).status == "Failure",
//          v1._2(1).fullyQualifiedName == "hello.MyCoreTests.java17Test",
//          v1._2(1).status == "Success"
//        )
      }
    }

    test("compileApis") {
      val resourcePathCompile = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "java-scala-11"
      test("jdk11java") {
        val baos = new ByteArrayOutputStream()
        val eval =
          UnitTester(JavaJdk11DoesntCompile, resourcePathCompile, errStream = new PrintStream(baos))
        val Left(result) = eval.apply(JavaJdk11DoesntCompile.javamodule.compile): @unchecked
        assert(baos.toString.contains("cannot find symbol"))
        assert(baos.toString.contains("method indent"))
      }

      test("jdk17java") {
        val eval = UnitTester(JavaJdk17Compiles, resourcePathCompile)
        val Right(result) = eval.apply(JavaJdk17Compiles.javamodule.compile): @unchecked
      }

      // This doesn't work because Zinc doesn't apply the javaHome config to
      // the Scala compiler JVM, which always runs in-memory https://github.com/sbt/zinc/discussions/1498
//      test("jdk11scala"){
//        val baos = new ByteArrayOutputStream()
//        val eval = UnitTester(JavaJdk11DoesntCompile, resourcePathCompile)
//        val Left(result) = eval.apply(JavaJdk11DoesntCompile.scalamodule.compile)
//      }

      test("jdk17scala") {
        val eval = UnitTester(JavaJdk17Compiles, resourcePathCompile)
        val Right(result) = eval.apply(JavaJdk17Compiles.scalamodule.compile): @unchecked
      }
    }

  }
}
