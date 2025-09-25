package mill
package kotlinlib

import mill.scalalib.TestModule
import mill.testkit.{TestBaseModule, UnitTester}
import mill.api.Result
import utest._

object HelloWorldTests extends TestSuite {

  val kotlinVersions = Seq("1.9.24", "2.0.20", "2.1.0")

  object HelloWorldKotlin extends TestBaseModule {
    trait MainCross extends KotlinModule with Cross.Module[String] {
      def kotlinVersion = crossValue
      override def mainClass = Some("hello.HelloKt")

      object test extends KotlinTests with TestModule.Junit4 {
        override def ivyDeps = super.ivyDeps() ++ Agg(
          mvn"org.jetbrains.kotlin:kotlin-test-junit:${this.kotlinVersion()}"
        )
      }
      object kotest extends KotlinTests with TestModule.Junit5 {
        override def ivyDeps = super.ivyDeps() ++ Agg(
          mvn"io.kotest:kotest-runner-junit5-jvm:5.9.1"
        )
      }
    }
    object main extends Cross[MainCross](kotlinVersions)
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world-kotlin"

  def testEval() = UnitTester(HelloWorldKotlin, resourcePath)
  def tests: Tests = Tests {
    test("compile") {
      val eval = testEval()

      HelloWorldKotlin.main.crossModules.foreach(m => {
        val Right(result) = eval.apply(m.compile)

        assert(
          os.walk(result.value.classes.path).exists(_.last == "HelloKt.class")
        )
      })
    }
    test("testCompile") {
      val eval = testEval()

      HelloWorldKotlin.main.crossModules.foreach(m => {
        val Right(result1) = eval.apply(m.test.compile)

        assert(
          os.walk(result1.value.classes.path).exists(_.last == "HelloTest.class")
        )
      })
    }
    test("test") {
      val eval = testEval()

      HelloWorldKotlin.main.crossModules.foreach(m => {
        val Left(Result.Failure(_, Some(v1))) = eval.apply(m.test.test())

        assert(
          v1._2(0).fullyQualifiedName == "hello.tests.HelloTest.testFailure",
          v1._2(0).status == "Failure",
          v1._2(1).fullyQualifiedName == "hello.tests.HelloTest.testSuccess",
          v1._2(1).status == "Success"
        )
      })
    }
    test("kotest") {
      val eval = testEval()

      HelloWorldKotlin.main.crossModules.foreach(m => {
        val Right(discovered) = eval.apply(m.kotest.discoveredTestClasses)
        assert(discovered.value == Seq("hello.tests.FooTest"))

        val Left(Result.Failure(_, Some(v1))) = eval.apply(m.kotest.test())

        assert(
          v1._2(0).fullyQualifiedName == "hello.tests.FooTest",
          v1._2(0).status == "Success",
          v1._2(1).fullyQualifiedName == "hello.tests.FooTest",
          v1._2(1).status == "Failure"
        )
      })
    }
    test("failures") {
      val eval = testEval()

      val mainJava = HelloWorldKotlin.millSourcePath / "main/src/Hello.kt"

      HelloWorldKotlin.main.crossModules.foreach(m => {

        val Right(_) = eval.apply(m.compile)

        os.write.over(mainJava, os.read(mainJava) + "}")

        val Left(_) = eval.apply(m.compile)

        os.write.over(mainJava, os.read(mainJava).dropRight(1))

        val Right(_) = eval.apply(m.compile)
      })
    }
  }
}
