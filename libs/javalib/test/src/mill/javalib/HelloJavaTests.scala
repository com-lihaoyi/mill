package mill
package javalib

import mill.api.ExecResult
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest.*
import mill.api.Discover

object HelloJavaTests extends TestSuite {

  object HelloJava extends TestRootModule {
    object core extends JavaModule {
      override def docJarUseArgsFile = false
      object test extends JavaTests with TestModule.Junit4
    }
    object app extends JavaModule {
      override def docJarUseArgsFile = true
      override def moduleDeps = Seq(core)
      object test extends JavaTests with TestModule.Junit4
      object testJunit5 extends JavaTests with TestModule.Junit5 {
        override def mvnDeps: T[Seq[Dep]] = Task {
          super.mvnDeps() ++ Seq(mvn"org.junit.jupiter:junit-jupiter-params:5.7.0")
        }
      }
    }
    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-java"

  def testEval() = UnitTester(HelloJava, resourcePath)
  def tests: Tests = Tests {
    test("compile") {
      testEval().scoped { eval =>

        val Right(result1) = eval.apply(HelloJava.core.compile): @unchecked
        val Right(result2) = eval.apply(HelloJava.core.compile): @unchecked
        val Right(result3) = eval.apply(HelloJava.app.compile): @unchecked

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
    }

    test("semanticDbData") {
      val expectedFile1 =
        os.rel / "META-INF/semanticdb/core/src/Core.java.semanticdb"
      val expectedFile2 =
        os.rel / "hello/Core.class"

      test("fromScratch") {
        testEval().scoped { eval =>
          val Right(result) = eval.apply(HelloJava.core.semanticDbData): @unchecked

          val outputFiles =
            os.walk(result.value.path).filter(os.isFile).map(_.relativeTo(result.value.path))
          val dataPath = eval.outPath / "core/semanticDbDataDetailed.dest/data"

          assert(
            result.value.path == dataPath,
            outputFiles.nonEmpty,
            outputFiles.toSet == Set(expectedFile1, expectedFile2),
            result.evalCount > 0
          )

          // don't recompile if nothing changed
          val Right(result2) = eval.apply(HelloJava.core.semanticDbData): @unchecked
          assert(result2.evalCount == 0)
        }
      }
      test("incremental") {
        testEval().scoped { eval =>

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
          val Right(result) = eval.apply(HelloJava.core.semanticDbData): @unchecked

          val dataPath = eval.outPath / "core/semanticDbDataDetailed.dest/data"
          val outputFiles =
            os.walk(result.value.path).filter(os.isFile).map(_.relativeTo(result.value.path))

          val expectedFile2 =
            os.rel / "META-INF/semanticdb/core/src/hello/Second.java.semanticdb"
          val expectedFile3 =
            os.rel / "META-INF/semanticdb/core/src/hello/Third.java.semanticdb"
          assert(
            result.value.path == dataPath,
            outputFiles.nonEmpty,
            outputFiles.toSet == Set(
              expectedFile1,
              expectedFile2,
              expectedFile3,
              os.rel / "hello/Core.class",
              os.rel / "hello/Second.class",
              os.rel / "hello/Third.class"
            ),
            result.evalCount > 0
          )

          println(
            "================================delete one, keep one, change one================================"
          )
          // delete one, keep one, change one
          os.remove(secondFile)
          os.write.append(thirdFile, "  ")

          val Right(result2) = eval.apply(HelloJava.core.semanticDbData): @unchecked
          val files2 =
            os.walk(result2.value.path).filter(os.isFile).map(_.relativeTo(result2.value.path))
          assert(
            files2.toSet == Set(
              expectedFile1,
              expectedFile3,
              os.rel / "hello/Core.class",
              os.rel / "hello/Third.class"
            ),
            result2.evalCount > 0
          )
        }
      }
    }
    test("docJar") {
      test("withoutArgsFile") {
        testEval().scoped { eval =>
          val Right(result) = eval.apply(HelloJava.core.docJar): @unchecked
          assert(
            os.proc("jar", "tf", result.value.path).call().out.lines().contains("hello/Core.html")
          )
        }
      }
      test("withArgsFile") {
        testEval().scoped { eval =>
          val Right(result) = eval.apply(HelloJava.app.docJar): @unchecked
          assert(
            os.proc("jar", "tf", result.value.path).call().out.lines().contains("hello/Main.html")
          )
        }
      }
    }
    test("test") - {
      testEval().scoped { eval =>

        val Left(_: ExecResult.Failure[_]) =
          eval.apply(HelloJava.core.test.testForked()): @unchecked

        //      assert(
        //        v1._2(0).fullyQualifiedName == "hello.MyCoreTests.java11Test",
        //        v1._2(1).fullyQualifiedName == "hello.MyCoreTests.java17Test",
        //        v1._2(2).fullyQualifiedName == "hello.MyCoreTests.lengthTest",
        //        v1._2(2).status == "Success",
        //        v1._2(3).fullyQualifiedName == "hello.MyCoreTests.msgTest",
        //        v1._2(3).status == "Failure"
        //      )

        val Right(result2) = eval.apply(HelloJava.app.test.testForked()): @unchecked

        assert(
          result2.value.results(0).fullyQualifiedName == "hello.MyAppTests.appTest",
          result2.value.results(0).status == "Success",
          result2.value.results(1).fullyQualifiedName == "hello.MyAppTests.coreTest",
          result2.value.results(1).status == "Success"
        )

        val Right(result3) = eval.apply(HelloJava.app.testJunit5.testForked()): @unchecked

        val testResults =
          result3.value.results.map(t => (t.fullyQualifiedName, t.selector, t.status)).sorted
        val expected = Seq(
          ("hello.Junit5TestsA", "coreTest()", "Success"),
          ("hello.Junit5TestsA", "palindromes(String):1", "Success"),
          ("hello.Junit5TestsA", "palindromes(String):2", "Success"),
          ("hello.Junit5TestsA", "skippedTest()", "Skipped"),
          ("hello.Junit5TestsB", "packagePrivateTest()", "Success")
        )

        assert(testResults == expected)
      }
    }
    test("failures") {
      testEval().scoped { eval =>

        val mainJava = HelloJava.moduleDir / "app/src/Main.java"
        val coreJava = HelloJava.moduleDir / "core/src/Core.java"

        val Right(_) = eval.apply(HelloJava.core.compile): @unchecked
        val Right(_) = eval.apply(HelloJava.app.compile): @unchecked

        os.write.over(mainJava, os.read(mainJava) + "}")

        val Right(_) = eval.apply(HelloJava.core.compile): @unchecked
        val Left(_) = eval.apply(HelloJava.app.compile): @unchecked

        os.write.over(coreJava, os.read(coreJava) + "}")

        val Left(_) = eval.apply(HelloJava.core.compile): @unchecked
        val Left(_) = eval.apply(HelloJava.app.compile): @unchecked

        os.write.over(mainJava, os.read(mainJava).dropRight(1))
        os.write.over(coreJava, os.read(coreJava).dropRight(1))

        val Right(_) = eval.apply(HelloJava.core.compile): @unchecked
        val Right(_) = eval.apply(HelloJava.app.compile): @unchecked
      }
    }
  }
}
