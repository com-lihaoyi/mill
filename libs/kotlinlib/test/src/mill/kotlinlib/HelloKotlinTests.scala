package mill
package kotlinlib

import mill.javalib.TestModule
import mill.testkit.{TestRootModule, UnitTester}
import mill.api.ExecResult
import mill.api.Discover
import utest.*

object HelloKotlinTests extends TestSuite {

  val crossMatrix = for {
    kotlinVersion <- Seq("1.9.24", "2.0.20", "2.1.20", Versions.kotlinVersion).distinct
    embeddable <- Seq(false, true)
  } yield (kotlinVersion, embeddable)

  val junit5Version = sys.props.getOrElse("TEST_JUNIT5_VERSION", "5.9.1")

  object HelloKotlin extends TestRootModule {
    // crossValue - test different Kotlin versions
    // crossValue2 - test with/without the kotlin embeddable compiler
    trait KotlinVersionCross extends KotlinModule with Cross.Module2[String, Boolean] {
      def kotlinVersion = crossValue

      override def kotlinUseEmbeddableCompiler: Task[Boolean] = Task { crossValue2 }

      override def mainClass = Some("hello.HelloKt")

      object test extends KotlinTests with TestModule.Junit4 {
        override def mvnDeps = super.mvnDeps() ++ Seq(
          mvn"org.jetbrains.kotlin:kotlin-test-junit:${this.kotlinVersion()}"
        )
      }
      object kotest extends KotlinTests with TestModule.Junit5 {
        override def mvnDeps = super.mvnDeps() ++ Seq(
          mvn"io.kotest:kotest-runner-junit5:${junit5Version}"
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
      testEval().scoped { eval =>
        HelloKotlin.main.crossModules.foreach(m => {
          val Right(compiler) = eval.apply(m.kotlinCompilerMvnDeps): @unchecked

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
    }

    test("testCompile") {
      testEval().scoped { eval =>
        HelloKotlin.main.crossModules.foreach(m => {
          val Right(compiler) = eval.apply(m.test.kotlinCompilerMvnDeps): @unchecked

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
    }

    test("test") {
      testEval().scoped { eval =>
        HelloKotlin.main.crossModules.foreach(m => {
          val Left(_: ExecResult.Failure[_]) = eval.apply(m.test.testForked()): @unchecked
        })
      }
    }
    test("kotest") {
      testEval().scoped { eval =>
        HelloKotlin.main.crossModules.foreach(m => {
          val Right(discovered) = eval.apply(m.kotest.discoveredTestClasses): @unchecked
          assert(discovered.value == Seq("hello.tests.FooTest"))

          val Left(_: ExecResult.Failure[_]) = eval.apply(m.kotest.testForked()): @unchecked
        })
      }
    }

    test("failures") {
      testEval().scoped { eval =>

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
}
