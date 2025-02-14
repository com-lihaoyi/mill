package mill
package kotlinlib

import mill.scalalib.TestModule
import mill.testkit.{TestBaseModule, UnitTester}
import mill.api.ExecResult
import mill.define.Discover
import utest.*

object HelloWorldEmbeddableTests extends TestSuite {

  val kotlinVersions = Seq("1.9.24", "2.0.20", "2.1.0")

  object HelloWorldKotlinEmbeddable extends TestBaseModule {
    trait MainCross extends KotlinModule with Cross.Module[String] {
      def kotlinVersion = crossValue

      override def kotlinCompilerEmbeddable: Task[Boolean] = Task { true }

      override def mainClass = Some("hello.HelloKt")

      object test extends KotlinTests with TestModule.Junit4 {
        override def ivyDeps = super.ivyDeps() ++ Seq(
          ivy"org.jetbrains.kotlin:kotlin-test-junit:${this.kotlinVersion()}"
        )
      }
      object kotest extends KotlinTests with TestModule.Junit5 {
        override def ivyDeps = super.ivyDeps() ++ Seq(
          ivy"io.kotest:kotest-runner-junit5-jvm:5.9.1"
        )
      }
    }
    object main extends Cross[MainCross](kotlinVersions)

    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world-kotlin"

  def testEval() = UnitTester(HelloWorldKotlinEmbeddable, resourcePath)
  def tests: Tests = Tests {
    test("compile") {
      val eval = testEval()

      HelloWorldKotlinEmbeddable.main.crossModules.foreach(m => {
        val Right(compiler) = eval.apply(m.kotlinCompilerIvyDeps): @unchecked

        assert(
          compiler.value.map(_.dep.module).map(m => m.organization.value -> m.name.value)
            .contains("org.jetbrains.kotlin" -> "kotlin-compiler-embeddable")
        )

        val Right(result) = eval.apply(m.compile): @unchecked

        assert(
          os.walk(result.value.classes.path).exists(_.last == "HelloKt.class")
        )
      })
    }
    test("testCompile") {
      val eval = testEval()

      HelloWorldKotlinEmbeddable.main.crossModules.foreach(m => {
        val Right(compiler) = eval.apply(m.test.kotlinCompilerIvyDeps): @unchecked

        assert(
          compiler.value.map(_.dep.module).map(m => m.organization.value -> m.name.value)
            .contains("org.jetbrains.kotlin" -> "kotlin-compiler-embeddable")
        )

        val Right(result1) = eval.apply(m.test.compile): @unchecked

        assert(
          os.walk(result1.value.classes.path).exists(_.last == "HelloTest.class")
        )
      })
    }
    test("test") {
      val eval = testEval()

      HelloWorldKotlinEmbeddable.main.crossModules.foreach(m => {
        val Left(ExecResult.Failure(_)) = eval.apply(m.test.test()): @unchecked
      })
    }
    test("kotest") {
      val eval = testEval()

      HelloWorldKotlinEmbeddable.main.crossModules.foreach(m => {
        val Right(discovered) = eval.apply(m.kotest.discoveredTestClasses): @unchecked
        assert(discovered.value == Seq("hello.tests.FooTest"))

        val Left(ExecResult.Failure(_)) = eval.apply(m.kotest.test()): @unchecked
      })
    }
    test("failures") {
      val eval = testEval()

      val mainJava = HelloWorldKotlinEmbeddable.moduleDir / "main/src/Hello.kt"

      HelloWorldKotlinEmbeddable.main.crossModules.foreach(m => {

        val Right(_) = eval.apply(m.compile): @unchecked

        os.write.over(mainJava, os.read(mainJava) + "}")

        val Left(_) = eval.apply(m.compile): @unchecked

        os.write.over(mainJava, os.read(mainJava).dropRight(1))

        val Right(_) = eval.apply(m.compile): @unchecked
      })
    }
  }
}
