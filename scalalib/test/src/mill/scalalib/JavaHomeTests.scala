package mill.scalalib

import mill.define.Task
import mill.define.Args
import mill.api.Result
import mill.define.ModuleRef
import mill.testkit.{TestBaseModule, UnitTester}
import utest._

object JavaHomeTests extends TestSuite {

  object HelloJavaJavaHome11Override extends TestBaseModule {
    object ZincWorkerJava11 extends ZincWorkerModule {
      def jvmId = "temurin:11.0.24"
    }

    object core extends JavaModule {
      override def zincWorker: ModuleRef[ZincWorkerModule] = ModuleRef(ZincWorkerJava11)
      override def docJarUseArgsFile = false
      object test extends JavaTests with TestModule.Junit4
    }
  }

  object HelloJavaJavaHome17Override extends TestBaseModule {
    object ZincWorkerJava17 extends ZincWorkerModule {
      def jvmId = "temurin:17.0.9"
    }

    object core extends JavaModule {
      override def zincWorker: ModuleRef[ZincWorkerModule] = ModuleRef(ZincWorkerJava17)
      override def docJarUseArgsFile = false
      object test extends JavaTests with TestModule.Junit4
    }
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-java"

  def tests: Tests = Tests {

    test("javaHome") {
      test("compile11") {
        val eval = UnitTester(HelloJavaJavaHome11Override, resourcePath)

        val Right(result) = eval.apply(HelloJavaJavaHome11Override.core.compile)

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

        val Right(result) = eval.apply(HelloJavaJavaHome17Override.core.compile)

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
        val Right(_) = eval.apply(HelloJavaJavaHome11Override.core.run(Task.Anon(Args(path))))

        assert(
          os.read(path).startsWith("11.")
        )
      }
      test("run17") {
        val eval = UnitTester(HelloJavaJavaHome17Override, resourcePath)

        val path = eval.evaluator.workspace / "java.version"
        val Right(_) = eval.apply(HelloJavaJavaHome17Override.core.run(Task.Anon(Args(path))))

        assert(
          os.read(path).startsWith("17.")
        )
      }
      test("test11") {
        val eval = UnitTester(HelloJavaJavaHome11Override, resourcePath)

        val Left(Result.Failure(ref1, Some(v1))) =
          eval.apply(HelloJavaJavaHome11Override.core.test.test())

        assert(
          v1._2(0).fullyQualifiedName == "hello.MyCoreTests.java11Test",
          v1._2(0).status == "Success",
          v1._2(1).fullyQualifiedName == "hello.MyCoreTests.java17Test",
          v1._2(1).status == "Failure"
        )
      }
      test("test17") {
        val eval = UnitTester(HelloJavaJavaHome17Override, resourcePath)

        val Left(Result.Failure(ref1, Some(v1))) =
          eval.apply(HelloJavaJavaHome17Override.core.test.test())

        assert(
          v1._2(0).fullyQualifiedName == "hello.MyCoreTests.java11Test",
          v1._2(0).status == "Failure",
          v1._2(1).fullyQualifiedName == "hello.MyCoreTests.java17Test",
          v1._2(1).status == "Success"
        )
      }
    }

  }
}
