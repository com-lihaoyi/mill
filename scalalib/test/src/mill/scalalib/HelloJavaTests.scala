package mill
package scalalib

import mill.api.Result
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._
import mill.define.ModuleRef

object HelloJavaTests extends TestSuite {

  object HelloJava extends TestBaseModule {
    object core extends JavaModule {
      override def docJarUseArgsFile = false
      object test extends JavaTests with TestModule.Junit4
    }
    object app extends JavaModule {
      override def docJarUseArgsFile = true
      override def moduleDeps = Seq(core)
      object test extends JavaTests with TestModule.Junit4
      object testJunit5 extends JavaTests with TestModule.Junit5 {
        override def ivyDeps: T[Agg[Dep]] = Task {
          super.ivyDeps() ++ Agg(mvn"org.junit.jupiter:junit-jupiter-params:5.7.0")
        }
      }
    }
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-java"

  def testEval() = UnitTester(HelloJava, resourcePath)
  def tests: Tests = Tests {
    test("compile") {
      val eval = testEval()

      val Right(result1) = eval.apply(HelloJava.core.compile)
      val Right(result2) = eval.apply(HelloJava.core.compile)
      val Right(result3) = eval.apply(HelloJava.app.compile)

      assert(
        result1.value == result2.value,
        result2.evalCount == 0,
        result3.evalCount != 0,
        result3.evalCount != 0,
        os.walk(result1.value.classes.path).exists(_.last == "Core.class"),
        !os.walk(result1.value.classes.path).exists(_.last == "Main.class"),
        os.walk(result3.value.classes.path).exists(_.last == "Main.class"),
        !os.walk(result3.value.classes.path).exists(_.last == "Core.class")
      )
    }

    test("semanticDbData") {
      val expectedFile1 =
        os.rel / "META-INF/semanticdb/core/src/Core.java.semanticdb"

      test("fromScratch") {
        val eval = testEval()
        val Right(result) = eval.apply(HelloJava.core.semanticDbData)

        val outputFiles =
          os.walk(result.value.path).filter(os.isFile).map(_.relativeTo(result.value.path))
        val dataPath = eval.outPath / "core/semanticDbData.dest/data"

        assert(
          result.value.path == dataPath,
          outputFiles.nonEmpty,
          outputFiles == Seq(expectedFile1),
          result.evalCount > 0
        )

        // don't recompile if nothing changed
        val Right(result2) = eval.apply(HelloJava.core.semanticDbData)
        assert(result2.evalCount == 0)
      }
      test("incremental") {
        val eval = testEval()

        // create a second source file
        val secondFile = eval.evaluator.workspace / "core/src/hello/Second.java"
        os.write(
          secondFile,
          """package hello;
            |
            |public class Second {
            |    public static String msg() {
            |        return "Hello World";
            |    }
            |}
            |""".stripMargin,
          createFolders = true
        )
        val thirdFile = eval.evaluator.workspace / "core/src/hello/Third.java"
        os.write(
          thirdFile,
          """package hello;
            |
            |public class Third {
            |    public static String msg() {
            |        return "Hello World";
            |    }
            |}
            |""".stripMargin,
          createFolders = true
        )
        val Right(result) = eval.apply(HelloJava.core.semanticDbData)

        val dataPath = eval.outPath / "core/semanticDbData.dest/data"
        val outputFiles =
          os.walk(result.value.path).filter(os.isFile).map(_.relativeTo(result.value.path))

        val expectedFile2 =
          os.rel / "META-INF/semanticdb/core/src/hello/Second.java.semanticdb"
        val expectedFile3 =
          os.rel / "META-INF/semanticdb/core/src/hello/Third.java.semanticdb"
        assert(
          result.value.path == dataPath,
          outputFiles.nonEmpty,
          outputFiles.toSet == Set(expectedFile1, expectedFile2, expectedFile3),
          result.evalCount > 0
        )

        // delete one, keep one, change one
        os.remove(secondFile)
        os.write.append(thirdFile, "  ")

        val Right(result2) = eval.apply(HelloJava.core.semanticDbData)
        val files2 =
          os.walk(result2.value.path).filter(os.isFile).map(_.relativeTo(result2.value.path))
        assert(
          files2.toSet == Set(expectedFile1, expectedFile3),
          result2.evalCount > 0
        )
      }
    }
    test("docJar") {
      test("withoutArgsFile") {
        val eval = testEval()
        val Right(result) = eval.apply(HelloJava.core.docJar)
        assert(
          os.proc("jar", "tf", result.value.path).call().out.lines().contains("hello/Core.html")
        )
      }
      test("withArgsFile") {
        val eval = testEval()
        val Right(result) = eval.apply(HelloJava.app.docJar)
        assert(
          os.proc("jar", "tf", result.value.path).call().out.lines().contains("hello/Main.html")
        )
      }
    }
    test("test") - {
      val eval = testEval()

      val Left(Result.Failure(ref1, Some(v1))) = eval.apply(HelloJava.core.test.test())

      assert(
        v1._2(0).fullyQualifiedName == "hello.MyCoreTests.java11Test",
        v1._2(1).fullyQualifiedName == "hello.MyCoreTests.java17Test",
        v1._2(2).fullyQualifiedName == "hello.MyCoreTests.lengthTest",
        v1._2(2).status == "Success",
        v1._2(3).fullyQualifiedName == "hello.MyCoreTests.msgTest",
        v1._2(3).status == "Failure"
      )

      val Right(result2) = eval.apply(HelloJava.app.test.test())

      assert(
        result2.value._2(0).fullyQualifiedName == "hello.MyAppTests.appTest",
        result2.value._2(0).status == "Success",
        result2.value._2(1).fullyQualifiedName == "hello.MyAppTests.coreTest",
        result2.value._2(1).status == "Success"
      )

      val Right(result3) = eval.apply(HelloJava.app.testJunit5.test())

      val testResults =
        result3.value._2.map(t => (t.fullyQualifiedName, t.selector, t.status)).sorted
      val expected = Seq(
        ("hello.Junit5TestsA", "coreTest()", "Success"),
        ("hello.Junit5TestsA", "palindromes(String):1", "Success"),
        ("hello.Junit5TestsA", "palindromes(String):2", "Success"),
        ("hello.Junit5TestsA", "skippedTest()", "Skipped"),
        ("hello.Junit5TestsB", "packagePrivateTest()", "Success")
      )

      assert(testResults == expected)
    }
    test("failures") {
      val eval = testEval()

      val mainJava = HelloJava.millSourcePath / "app/src/Main.java"
      val coreJava = HelloJava.millSourcePath / "core/src/Core.java"

      val Right(_) = eval.apply(HelloJava.core.compile)
      val Right(_) = eval.apply(HelloJava.app.compile)

      os.write.over(mainJava, os.read(mainJava) + "}")

      val Right(_) = eval.apply(HelloJava.core.compile)
      val Left(_) = eval.apply(HelloJava.app.compile)

      os.write.over(coreJava, os.read(coreJava) + "}")

      val Left(_) = eval.apply(HelloJava.core.compile)
      val Left(_) = eval.apply(HelloJava.app.compile)

      os.write.over(mainJava, os.read(mainJava).dropRight(1))
      os.write.over(coreJava, os.read(coreJava).dropRight(1))

      val Right(_) = eval.apply(HelloJava.core.compile)
      val Right(_) = eval.apply(HelloJava.app.compile)
    }
  }
}
