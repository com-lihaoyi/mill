package mill.javalib

import mill.api.{Args, Discover, Task}
import mill.api.ExecResult
import mill.testkit.{TestRootModule, UnitTester}
import mill.util.TokenReaders.*
import utest.*

object JavaHomeTests extends TestSuite {

  object HelloJavaJavaHome11Override extends TestRootModule {
    object core extends JavaModule {
      def jvmId = "temurin:11.0.24"
      override def docJarUseArgsFile = false
      object test extends JavaTests with TestModule.Junit4
    }

    lazy val millDiscover = Discover[this.type]
  }

  object HelloJavaJavaHome17Override extends TestRootModule {

    object core extends JavaModule {
      def jvmId = "temurin:17.0.9"
      override def docJarUseArgsFile = false
      object test extends JavaTests with TestModule.Junit4
    }
    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-java"

  def tests: Tests = Tests {

    test("javaHome") {
      test("compile11") {
        UnitTester(HelloJavaJavaHome11Override, resourcePath).scoped { eval =>

          val Right(result) = eval.apply(HelloJavaJavaHome11Override.core.compile): @unchecked

          val coreClassFile = os.walk(result.value.classes.path).find(_.last == "Core.class")

          assert(
            coreClassFile.isDefined,

            // The first eight bytes are magic numbers followed by two bytes for major version and two bytes for minor version
            // We are overriding to java 11 which corresponds to class file version 55
            os.read.bytes(coreClassFile.get, 4, 4).toSeq == Seq[Byte](0, 0, 0, 55)
          )
        }
      }
      test("compile17") {
        UnitTester(HelloJavaJavaHome17Override, resourcePath).scoped { eval =>

          val Right(result) = eval.apply(HelloJavaJavaHome17Override.core.compile): @unchecked

          val coreClassFile = os.walk(result.value.classes.path).find(_.last == "Core.class")

          assert(
            coreClassFile.isDefined,

            // The first eight bytes are magic numbers followed by two bytes for major version and two bytes for minor version
            // We are overriding to java 17 which corresponds to class file version 67
            os.read.bytes(coreClassFile.get, 4, 4).toSeq == Seq[Byte](0, 0, 0, 61)
          )
        }
      }
      test("run11") {
        UnitTester(HelloJavaJavaHome11Override, resourcePath).scoped { eval =>

          val path = eval.evaluator.workspace / "java.version"
          val Right(_) =
            eval.apply(HelloJavaJavaHome11Override.core.run(Task.Anon(Args(path)))): @unchecked

          assert(
            os.read(path).startsWith("11.")
          )
        }
      }
      test("run17") {
        UnitTester(HelloJavaJavaHome17Override, resourcePath).scoped { eval =>

          val path = eval.evaluator.workspace / "java.version"
          val Right(_) =
            eval.apply(HelloJavaJavaHome17Override.core.run(Task.Anon(Args(path)))): @unchecked

          assert(
            os.read(path).startsWith("17.")
          )
        }
      }
      test("test11") {
        UnitTester(HelloJavaJavaHome11Override, resourcePath).scoped { eval =>

          val Left(ExecResult.Failure(_)) =
            eval.apply(HelloJavaJavaHome11Override.core.test.testForked()): @unchecked

          //        assert(
          //          v1._2(0).fullyQualifiedName == "hello.MyCoreTests.java11Test",
          //          v1._2(0).status == "Success",
          //          v1._2(1).fullyQualifiedName == "hello.MyCoreTests.java17Test",
          //          v1._2(1).status == "Failure"
          //        )
        }
      }
      test("test17") {
        UnitTester(HelloJavaJavaHome17Override, resourcePath).scoped { eval =>

          val Left(ExecResult.Failure(_)) =
            eval.apply(HelloJavaJavaHome17Override.core.test.testForked()): @unchecked

          //        assert(
          //          v1._2(0).fullyQualifiedName == "hello.MyCoreTests.java11Test",
          //          v1._2(0).status == "Failure",
          //          v1._2(1).fullyQualifiedName == "hello.MyCoreTests.java17Test",
          //          v1._2(1).status == "Success"
          //        )
        }
      }
    }
  }
}
