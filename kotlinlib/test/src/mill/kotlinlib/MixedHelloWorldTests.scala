package mill
package kotlinlib

import mill.api.ExecResult
import mill.define.Discover
import mill.scalalib.TestModule
import mill.testkit.{TestBaseModule, UnitTester}
import utest.*

object MixedHelloWorldTests extends TestSuite {

  val kotlinVersions = if (scala.util.Properties.isJavaAtLeast(9)) {
    Seq("1.9.24", "2.0.20")
  } else {
    Seq("1.0.0", "1.9.24", "2.0.20")
  }

  object MixedHelloWorldKotlin extends TestBaseModule {
    trait MainCross extends KotlinModule with Cross.Module[String] {
      def kotlinVersion = crossValue
      override def mainClass = Some("hello.JavaHello")

      object test extends KotlinTests with TestModule.Junit4 {
        override def ivyDeps = super.ivyDeps() ++ Seq(
          ivy"org.jetbrains.kotlin:kotlin-test-junit:${this.kotlinVersion()}"
        )
      }
    }
    object main extends Cross[MainCross](kotlinVersions)

    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath =
    os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "mixed-code-hello-world-kotlin"

  def testEval() = UnitTester(MixedHelloWorldKotlin, resourcePath)
  def tests: Tests = Tests {
    test("compile") {
      val eval = testEval()

      MixedHelloWorldKotlin.main.crossModules.foreach(m => {
        val Right(result) = eval.apply(m.compile): @unchecked

        assert(
          os.walk(result.value.classes.path).exists(_.last == "KotlinHelloKt.class"),
          os.walk(result.value.classes.path).exists(_.last == "JavaHello.class")
        )
      })
    }
    test("testCompile") {
      val eval = testEval()

      MixedHelloWorldKotlin.main.crossModules.foreach(m => {
        val Right(result1) = eval.apply(m.test.compile): @unchecked

        assert(
          os.walk(result1.value.classes.path).exists(_.last == "HelloTest.class")
        )
      })
    }
    test("test") {
      val eval = testEval()
      MixedHelloWorldKotlin.main.crossModules.foreach(m => {

        val Left(ExecResult.Failure(_)) = eval.apply(m.test.testForked()): @unchecked

//        assert(
//          v1._2(0).fullyQualifiedName == "hello.tests.HelloTest.testFailure",
//          v1._2(0).status == "Failure",
//          v1._2(1).fullyQualifiedName == "hello.tests.HelloTest.testSuccess",
//          v1._2(1).status == "Success"
//        )
      })
    }
    test("failures") {
      val eval = testEval()

      MixedHelloWorldKotlin.main.crossModules.foreach(m => {

        val mainJava =
          MixedHelloWorldKotlin.moduleDir / "main/src/hello/KotlinHello.kt"

        val Right(_) = eval.apply(m.compile): @unchecked

        os.write.over(mainJava, os.read(mainJava) + "}")

        val Left(_) = eval.apply(m.compile): @unchecked

        os.write.over(mainJava, os.read(mainJava).dropRight(1))

        val Right(_) = eval.apply(m.compile): @unchecked
      })
    }
  }
}
