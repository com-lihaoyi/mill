package mill
package kotlinlib

import mill.javalib.TestModule
import mill.testkit.{TestRootModule, UnitTester}
import mill.api.ExecResult
import mill.api.Discover
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}

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

    test("incrementalCompilation") {
      // Enable Kotlin Build Tools API debug logging to verify incremental compilation
      System.setProperty("kotlin.build-tools-api.log.level", "debug")

      // Capture output to verify which files are marked dirty
      val outStream = new ByteArrayOutputStream()
      val errStream = new ByteArrayOutputStream()

      def getDirtyFiles(out: ByteArrayOutputStream, err: ByteArrayOutputStream): Seq[String] = {
        val output = (out.toString + err.toString).linesIterator.toSeq
        output
          .filter(_.contains("is marked dirty"))
          .map(_.split("/").last.split(" ").head) // Extract filename from path
          .toSeq
      }

      UnitTester(
        HelloKotlin,
        resourcePath,
        outStream = new PrintStream(outStream, true),
        errStream = new PrintStream(errStream, true),
        debugEnabled = true
      ).scoped { eval =>
        // Test only Kotlin 2.1+ which uses the Build Tools API with incremental compilation
        // Use only one module (non-embeddable) to simplify output parsing
        val btApiModule = HelloKotlin.main.crossModules.find(m =>
          m.crossValue.startsWith("2.1") && !m.crossValue2
        ).get

        val srcDir = eval.evaluator.workspace / "main/src/hello"
        os.makeDir.all(srcDir)

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

        // First compile - full compilation (both files should be marked dirty)
        outStream.reset()
        errStream.reset()
        val Right(result1) = eval.apply(btApiModule.compile): @unchecked
        assert(
          os.walk(result1.value.classes.path).exists(_.last == "HelloKt.class"),
          os.walk(result1.value.classes.path).exists(_.last == "ExtraKt.class")
        )
        // On first compile, both files should be marked dirty
        val dirty1 = getDirtyFiles(outStream, errStream)
        assert(dirty1.sorted == Seq("Extra.kt", "Hello.kt"))

        // Second compile without changes - should be cached by Mill
        val Right(result2) = eval.apply(btApiModule.compile): @unchecked
        assert(result2.evalCount == 0)

        // Modify the extra file - should trigger incremental compilation
        os.write.over(
          extraFile,
          """package hello
            |
            |fun getExtraString(): String = "Extra Modified"
            |""".stripMargin
        )

        outStream.reset()
        errStream.reset()
        val Right(result3) = eval.apply(btApiModule.compile): @unchecked
        assert(result3.evalCount > 0)
        assert(os.walk(result3.value.classes.path).exists(_.last == "ExtraKt.class"))

        // Only Extra.kt should be marked dirty, Hello.kt should NOT be recompiled
        val dirty3 = getDirtyFiles(outStream, errStream)
        assert(dirty3 == Seq("Extra.kt"))

        // Add a new file - should trigger incremental compilation
        val anotherFile = srcDir / "Another.kt"
        os.write(
          anotherFile,
          """package hello
            |
            |fun getAnotherString(): String = "Another"
            |""".stripMargin,
          createFolders = true
        )

        outStream.reset()
        errStream.reset()
        val Right(result4) = eval.apply(btApiModule.compile): @unchecked
        assert(result4.evalCount > 0)
        assert(os.walk(result4.value.classes.path).exists(_.last == "AnotherKt.class"))

        // Only Another.kt should be marked dirty (new file)
        val dirty4 = getDirtyFiles(outStream, errStream)
        assert(dirty4 == Seq("Another.kt"))

        // Clean up
        os.remove(extraFile)
        os.remove(anotherFile)
      }
    }
  }
}
