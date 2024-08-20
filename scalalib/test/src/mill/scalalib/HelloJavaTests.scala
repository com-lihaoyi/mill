package mill
package scalalib

import mill.api.Result
import mill.testkit.TestEvaluator
import mill.testkit.MillTestKit
import utest._
import utest.framework.TestPath

object HelloJavaTests extends TestSuite {

  object HelloJava extends mill.testkit.BaseModule {
    def millSourcePath = MillTestKit.getSrcPathBase() / millOuterCtx.enclosing.split('.')

    object core extends JavaModule {
      override def docJarUseArgsFile = false
      object test extends JavaModuleTests with TestModule.Junit4
    }
    object app extends JavaModule {
      override def docJarUseArgsFile = true
      override def moduleDeps = Seq(core)
      object test extends JavaModuleTests with TestModule.Junit4
      object testJunit5 extends JavaModuleTests with TestModule.Junit5 {
        override def ivyDeps: T[Agg[Dep]] = T {
          super.ivyDeps() ++ Agg(ivy"org.junit.jupiter:junit-jupiter-params:5.7.0")
        }
      }
    }
  }
  val resourcePath = os.pwd / "scalalib" / "test" / "resources" / "hello-java"

  def init()(implicit tp: TestPath) = {
    val eval = new TestEvaluator(HelloJava)
    os.remove.all(HelloJava.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(HelloJava.millSourcePath / os.up)
    os.copy(resourcePath, HelloJava.millSourcePath)
    eval
  }
  def tests: Tests = Tests {
    test("compile") {
      val eval = init()

      val Right((res1, n1)) = eval.apply(HelloJava.core.compile)
      val Right((res2, 0)) = eval.apply(HelloJava.core.compile)
      val Right((res3, n2)) = eval.apply(HelloJava.app.compile)

      assert(
        res1 == res2,
        n1 != 0,
        n2 != 0,
        os.walk(res1.classes.path).exists(_.last == "Core.class"),
        !os.walk(res1.classes.path).exists(_.last == "Main.class"),
        os.walk(res3.classes.path).exists(_.last == "Main.class"),
        !os.walk(res3.classes.path).exists(_.last == "Core.class")
      )
    }
    test("semanticDbData") {
      val expectedFile1 =
        os.rel / "META-INF" / "semanticdb" / "core" / "src" / "Core.java.semanticdb"

      test("fromScratch") {
        val eval = init()
        val Right((result, evalCount)) = eval.apply(HelloJava.core.semanticDbData)

        val outputFiles = os.walk(result.path).filter(os.isFile).map(_.relativeTo(result.path))
        val dataPath = eval.outPath / "core" / "semanticDbData.dest" / "data"

        assert(
          result.path == dataPath,
          outputFiles.nonEmpty,
          outputFiles == Seq(expectedFile1),
          evalCount > 0
        )

        // don't recompile if nothing changed
        val Right((_, unchangedEvalCount)) = eval.apply(HelloJava.core.semanticDbData)
        assert(unchangedEvalCount == 0)
      }
      test("incremental") {
        val eval = init()

        // create a second source file
        val secondFile = eval.evaluator.workspace / "core" / "src" / "hello" / "Second.java"
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
        val thirdFile = eval.evaluator.workspace / "core" / "src" / "hello" / "Third.java"
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
        val Right((result, evalCount)) = eval.apply(HelloJava.core.semanticDbData)

        val dataPath = eval.outPath / "core" / "semanticDbData.dest" / "data"
        val outputFiles = os.walk(result.path).filter(os.isFile).map(_.relativeTo(result.path))

        val expectedFile2 =
          os.rel / "META-INF" / "semanticdb" / "core" / "src" / "hello" / "Second.java.semanticdb"
        val expectedFile3 =
          os.rel / "META-INF" / "semanticdb" / "core" / "src" / "hello" / "Third.java.semanticdb"
        assert(
          result.path == dataPath,
          outputFiles.nonEmpty,
          outputFiles.toSet == Set(expectedFile1, expectedFile2, expectedFile3),
          evalCount > 0
        )

        // delete one, keep one, change one
        os.remove(secondFile)
        os.write.append(thirdFile, "  ")

        val Right((result2, changedEvalCount)) = eval.apply(HelloJava.core.semanticDbData)
        val files2 = os.walk(result2.path).filter(os.isFile).map(_.relativeTo(result2.path))
        assert(
          files2.toSet == Set(expectedFile1, expectedFile3),
          changedEvalCount > 0
        )
      }
    }
    test("docJar") {
      test("withoutArgsFile") {
        val eval = init()
        val Right((ref1, _)) = eval.apply(HelloJava.core.docJar)
        assert(os.proc("jar", "tf", ref1.path).call().out.lines().contains("hello/Core.html"))
      }
      test("withArgsFile") {
        val eval = init()
        val Right((ref2, _)) = eval.apply(HelloJava.app.docJar)
        assert(os.proc("jar", "tf", ref2.path).call().out.lines().contains("hello/Main.html"))
      }
    }
    test("test") - {
      val eval = init()

      val Left(Result.Failure(ref1, Some(v1))) = eval.apply(HelloJava.core.test.test())

      assert(
        v1._2(0).fullyQualifiedName == "hello.MyCoreTests.lengthTest",
        v1._2(0).status == "Success",
        v1._2(1).fullyQualifiedName == "hello.MyCoreTests.msgTest",
        v1._2(1).status == "Failure"
      )

      val Right((v2, _)) = eval.apply(HelloJava.app.test.test())

      assert(
        v2._2(0).fullyQualifiedName == "hello.MyAppTests.appTest",
        v2._2(0).status == "Success",
        v2._2(1).fullyQualifiedName == "hello.MyAppTests.coreTest",
        v2._2(1).status == "Success"
      )

      val Right((v3, _)) = eval.apply(HelloJava.app.testJunit5.test())

      val testResults = v3._2.map(t => (t.fullyQualifiedName, t.selector, t.status)).sorted
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
      val eval = init()

      val mainJava = HelloJava.millSourcePath / "app" / "src" / "Main.java"
      val coreJava = HelloJava.millSourcePath / "core" / "src" / "Core.java"

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
