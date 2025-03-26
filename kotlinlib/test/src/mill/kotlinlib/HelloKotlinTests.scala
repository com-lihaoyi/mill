package mill
package kotlinlib

import mill.scalalib.TestModule
import mill.testkit.{TestBaseModule, UnitTester}
import mill.api.ExecResult
import mill.define.Discover
import utest.*

object HelloKotlinTests extends TestSuite {

  val crossMatrix = for {
    kotlinVersion <- Seq("1.9.24", "2.0.20", "2.1.0")
    embeddable <- Seq(false, true)
  } yield (kotlinVersion, embeddable)

  val junit5Version = sys.props.getOrElse("TEST_JUNIT5_VERSION", "5.9.1")

  object HelloKotlin extends TestBaseModule {
    // crossValue - test different Kotlin versions
    // crossValue2 - test with/without the kotlin embeddable compiler
    trait KotlinVersionCross extends KotlinModule with Cross.Module2[String, Boolean] {
      def kotlinVersion = crossValue

      override def kotlinUseEmbeddableCompiler: Task[Boolean] = Task { crossValue2 }

      override def mainClass = Some("hello.HelloKt")

      object test extends KotlinTests with TestModule.Junit4 {
        override def ivyDeps = super.ivyDeps() ++ Seq(
          ivy"org.jetbrains.kotlin:kotlin-test-junit:${this.kotlinVersion()}"
        )
      }
      object kotest extends KotlinTests with TestModule.Junit5 {
        override def ivyDeps = super.ivyDeps() ++ Seq(
          ivy"io.kotest:kotest-runner-junit5-jvm:${junit5Version}"
        )
      }
    }
    object main extends Cross[KotlinVersionCross](crossMatrix)

    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-kotlin"

  def testEval() = UnitTester(HelloKotlin, resourcePath)

  def tests: Tests = Tests {

    def compilerDep(embeddable: Boolean) =
      if (embeddable) "org.jetbrains.kotlin" -> "kotlin-compiler-embeddable"
      else "org.jetbrains.kotlin" -> "kotlin-compiler"

    test("compile") {
      val eval = testEval()

      HelloKotlin.main.crossModules.foreach(m => {
        val Right(compiler) = eval.apply(m.kotlinCompilerIvyDeps): @unchecked

        assert(
          compiler.value.map(_.dep.module)
            .map(m => m.organization.value -> m.name.value)
            .contains(compilerDep(m.crossValue2))
        )

        val Right(result) = eval.apply(m.compile): @unchecked

        assert(
          os.walk(result.value.classes.path).exists(_.last == "HelloKt.class")
        )
      })
    }

    test("testCompile") {
      val eval = testEval()

      HelloKotlin.main.crossModules.foreach(m => {
        val Right(compiler) = eval.apply(m.test.kotlinCompilerIvyDeps): @unchecked

        assert(
          compiler.value.map(_.dep.module)
            .map(m => m.organization.value -> m.name.value)
            .contains(compilerDep(m.crossValue2))
        )

        val Right(result1) = eval.apply(m.test.compile): @unchecked

        assert(
          os.walk(result1.value.classes.path).exists(_.last == "HelloTest.class")
        )
      })
    }

    test("test") {
      val eval = testEval()

      HelloKotlin.main.crossModules.foreach(m => {
        val Left(ExecResult.Failure(_)) = eval.apply(m.test.testForked()): @unchecked
      })
    }
    test("kotest") {
      val eval = testEval()

      HelloKotlin.main.crossModules.foreach(m => {
        val Right(discovered) = eval.apply(m.kotest.discoveredTestClasses): @unchecked
        assert(discovered.value == Seq("hello.tests.FooTest"))

        val Left(ExecResult.Failure(_)) = eval.apply(m.kotest.testForked()): @unchecked
      })
    }

    test("failures") {
      val eval = testEval()

      val mainJava = HelloKotlin.moduleDir / "main/src/Hello.kt"

      HelloKotlin.main.crossModules.foreach(m => {

        val Right(_) = eval.apply(m.compile): @unchecked

        os.write.over(mainJava, os.read(mainJava) + "}")

        val Left(_) = eval.apply(m.compile): @unchecked

        os.write.over(mainJava, os.read(mainJava).dropRight(1))

        val Right(_) = eval.apply(m.compile): @unchecked
      })
    }
  }
}
