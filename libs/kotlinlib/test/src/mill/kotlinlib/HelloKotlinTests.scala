package mill
package kotlinlib

import mill.javalib.TestModule
import mill.testkit.{TestRootModule, UnitTester}
import mill.api.ExecResult
import mill.api.Discover
import utest.*

object HelloKotlinTests extends TestSuite {

  val crossMatrix = for {
    kotlinVersion <- Seq("1.9.24", "2.0.20", "2.1.20", "2.3.0", Versions.kotlinVersion).distinct
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

    def btApiSupported(kotlinVersion: String): Boolean = {
      val parts = kotlinVersion.split("[.-]").toSeq
      val major = parts.headOption.flatMap(_.toIntOption).getOrElse(0)
      val minor = parts.lift(1).flatMap(_.toIntOption).getOrElse(0)
      major > 2 || (major == 2 && minor >= 3)
    }

    test("compile") {
      testEval().scoped { eval =>
        HelloKotlin.main.crossModules.foreach(m => {
          val Right(compiler) = eval.apply(m.kotlinCompilerMvnDeps).runtimeChecked

          assert(
            compiler.value.map(_.dep.module)
              .map(m => m.organization.value -> m.name.value)
              .contains(compilerDep(m.crossValue2))
          )

          val Right(result) = eval.apply(m.compile).runtimeChecked

          assert(
            os.walk(result.value.classes.path).exists(_.last == "HelloKt.class")
          )
        })
      }
    }

    test("btApiAndCompilerDeps") {
      testEval().scoped { eval =>
        HelloKotlin.main.crossModules.foreach(m => {
          val expectedBtApi = btApiSupported(m.crossValue) && m.crossValue2

          val Right(useBtApi) = eval.apply(m.kotlincUseBtApi).runtimeChecked
          assert(useBtApi.value == expectedBtApi)

          val Right(compilerDeps) = eval.apply(m.kotlinCompilerMvnDeps).runtimeChecked
          val modules = compilerDeps.value.map(_.dep.module)
            .map(mod => mod.organization.value -> mod.name.value)
            .toSet

          assert(modules.contains(compilerDep(m.crossValue2)))

          val btApiModules = Set(
            "org.jetbrains.kotlin" -> "kotlin-build-tools-api",
            "org.jetbrains.kotlin" -> "kotlin-build-tools-impl"
          )

          if (expectedBtApi) {
            assert(btApiModules.subsetOf(modules))
          } else {
            assert(btApiModules.intersect(modules).isEmpty)
          }
        })
      }
    }

    test("testCompile") {
      testEval().scoped { eval =>
        HelloKotlin.main.crossModules.foreach(m => {
          val Right(compiler) = eval.apply(m.test.kotlinCompilerMvnDeps).runtimeChecked

          assert(
            compiler.value.map(_.dep.module)
              .map(m => m.organization.value -> m.name.value)
              .contains(compilerDep(m.crossValue2))
          )

          val Right(result1) = eval.apply(m.test.compile).runtimeChecked

          assert(
            os.walk(result1.value.classes.path).exists(_.last == "HelloTest.class")
          )
        })
      }
    }

    test("test") {
      testEval().scoped { eval =>
        HelloKotlin.main.crossModules.foreach(m => {
          val Left(_: ExecResult.Failure[_]) = eval.apply(m.test.testForked()).runtimeChecked
        })
      }
    }
    test("kotest") {
      testEval().scoped { eval =>
        HelloKotlin.main.crossModules.foreach(m => {
          val Right(discovered) = eval.apply(m.kotest.discoveredTestClasses).runtimeChecked
          assert(discovered.value == Seq("hello.tests.FooTest"))

          val Left(_: ExecResult.Failure[_]) = eval.apply(m.kotest.testForked()).runtimeChecked
        })
      }
    }

    test("failures") {
      testEval().scoped { eval =>

        val mainJava = HelloKotlin.moduleDir / "main/src/Hello.kt"

        HelloKotlin.main.crossModules.foreach(m => {

          val Right(_) = eval.apply(m.compile).runtimeChecked

          os.write.over(mainJava, os.read(mainJava) + "}")

          val Left(_) = eval.apply(m.compile).runtimeChecked

          os.write.over(mainJava, os.read(mainJava).dropRight(1))

          val Right(_) = eval.apply(m.compile).runtimeChecked
        })
      }
    }

    test("incrementalCompilation") {
      UnitTester(
        HelloKotlin,
        resourcePath,
        debugEnabled = true
      ).scoped { eval =>
        // Test only Kotlin 2.3+ with embeddable compiler, where Build Tools API is enabled.
        // Use only one module to simplify output parsing.
        val btApiModule = HelloKotlin.main.crossModules.find(m =>
          btApiSupported(m.crossValue) && m.crossValue2
        ).get

        val srcDir = eval.evaluator.workspace / "main/src/hello"
        os.makeDir.all(srcDir)

        def classPath(result: mill.javalib.api.CompilationResult, className: String): os.Path =
          result.classes.path / os.RelPath(className)

        def fileMtimeMillis(path: os.Path): Long = os.mtime(path)

        // Create an additional source file for incremental compilation testing
        val extraFile = srcDir / "Extra.kt"
        os.write(
          extraFile,
          """package hello
            |
            |fun getExtraString(): String = "Extra"
            |""".stripMargin,
          createFolders = true
        )

        // First compile - full compilation
        val Right(result1) = eval.apply(btApiModule.compile).runtimeChecked
        val helloClass = classPath(result1.value, "hello/HelloKt.class")
        val extraClass = classPath(result1.value, "hello/ExtraKt.class")
        assert(
          os.exists(helloClass),
          os.exists(extraClass)
        )
        val helloMtime1 = fileMtimeMillis(helloClass)
        val extraMtime1 = fileMtimeMillis(extraClass)

        // Second compile without changes - should be cached by Mill
        val Right(result2) = eval.apply(btApiModule.compile).runtimeChecked
        assert(result2.evalCount == 0)

        // Modify the extra file - should trigger incremental compilation
        Thread.sleep(1100L)
        os.write.over(
          extraFile,
          """package hello
            |
            |fun getExtraString(): String = "Extra Modified"
            |""".stripMargin
        )

        val Right(result3) = eval.apply(btApiModule.compile).runtimeChecked
        assert(result3.evalCount > 0)
        assert(os.exists(extraClass))

        val helloMtime2 = fileMtimeMillis(helloClass)
        val extraMtime2 = fileMtimeMillis(extraClass)
        assert(extraMtime2 > extraMtime1)
        assert(helloMtime2 == helloMtime1)

        // Add a new file - should trigger incremental compilation
        val anotherFile = srcDir / "Another.kt"
        Thread.sleep(1100L)
        os.write(
          anotherFile,
          """package hello
            |
            |fun getAnotherString(): String = "Another"
            |""".stripMargin,
          createFolders = true
        )

        val Right(result4) = eval.apply(btApiModule.compile).runtimeChecked
        assert(result4.evalCount > 0)
        val anotherClass = classPath(result4.value, "hello/AnotherKt.class")
        assert(os.exists(anotherClass))

        val helloMtime3 = fileMtimeMillis(helloClass)
        val extraMtime3 = fileMtimeMillis(extraClass)
        assert(helloMtime3 == helloMtime2)
        assert(extraMtime3 == extraMtime2)

        // Clean up
        os.remove(extraFile)
        os.remove(anotherFile)
      }
    }
  }
}
