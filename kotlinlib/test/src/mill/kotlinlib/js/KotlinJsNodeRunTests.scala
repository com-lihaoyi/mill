package mill
package kotlinlib
package js

import mill.eval.EvaluatorPaths
import mill.testkit.{TestBaseModule, UnitTester}
import utest.{TestSuite, Tests, test}

object KotlinJsNodeRunTests extends TestSuite {

  private val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "kotlin-js"
  private val kotlinVersion = "1.9.25"
  private val expectedSuccessOutput = "Hello, world"

  object module extends TestBaseModule {

    private val matrix = for {
      splits <- Seq(true, false)
      modules <- Seq("no", "plain", "es", "amd", "commonjs", "umd")
    } yield (splits, modules)

    trait KotlinJsModuleKindCross extends KotlinJsModule with Cross.Module2[Boolean, String] {

      def kotlinVersion = KotlinJsNodeRunTests.kotlinVersion

      override def moduleKind = crossValue2 match {
        case "no" => ModuleKind.NoModule
        case "plain" => ModuleKind.PlainModule
        case "es" => ModuleKind.ESModule
        case "amd" => ModuleKind.AMDModule
        case "commonjs" => ModuleKind.CommonJSModule
        case "umd" => ModuleKind.UMDModule
      }

      override def moduleDeps = Seq(module.bar)
      override def splitPerModule = crossValue
      override def kotlinJsRunTarget = Some(RunTarget.Node)
    }

    object bar extends KotlinJsModule {
      def kotlinVersion = KotlinJsNodeRunTests.kotlinVersion
    }

    object foo extends Cross[KotlinJsModuleKindCross](matrix)
  }

  private def testEval() = UnitTester(module, resourcePath)

  def tests: Tests = Tests {
    // region with split per module

    test("run { split per module / plain module }") {
      val eval = testEval()

      // plain modules cannot handle the dependencies, so if there are multiple js files, it will fail
      val Left(_) = eval.apply(module.foo(true, "plain").run())
    }

    test("run { split per module / es module }") {
      val eval = testEval()

      val command = module.foo(true, "es").run()
      val Right(_) = eval.apply(command)

      assertLogContains(eval, command, expectedSuccessOutput)
    }

    test("run { split per module / amd module }") {
      val eval = testEval()

      // amd modules have "define" method, it is not known by Node.js
      val Left(_) = eval.apply(module.foo(true, "amd").run())
    }

    test("run { split per module / commonjs module }") {
      val eval = testEval()

      val command = module.foo(true, "commonjs").run()
      val Right(_) = eval.apply(command)

      assertLogContains(eval, command, expectedSuccessOutput)
    }

    test("run { split per module / umd module }") {
      val eval = testEval()

      val command = module.foo(true, "umd").run()
      val Right(_) = eval.apply(command)

      assertLogContains(eval, command, expectedSuccessOutput)
    }

    test("run { split per module / no module }") {
      val eval = testEval()

      val Left(_) = eval.apply(module.foo(true, "no").run())
    }

    // endregion

    // region without split per module

    test("run { no split per module / plain module }") {
      val eval = testEval()

      val command = module.foo(false, "plain").run()
      val Right(_) = eval.apply(command)

      assertLogContains(eval, command, expectedSuccessOutput)
    }

    test("run { no split per module / es module }") {
      val eval = testEval()

      val command = module.foo(false, "es").run()
      val Right(_) = eval.apply(command)

      assertLogContains(eval, command, expectedSuccessOutput)
    }

    test("run { no split per module / amd module }") {
      val eval = testEval()

      // amd modules have "define" method, it is not known by Node.js
      val Left(_) = eval.apply(module.foo(false, "amd").run())
    }

    test("run { no split per module / commonjs module }") {
      val eval = testEval()

      val command = module.foo(false, "commonjs").run()
      val Right(_) = eval.apply(command)

      assertLogContains(eval, command, expectedSuccessOutput)
    }

    test("run { no split per module / umd module }") {
      val eval = testEval()

      val command = module.foo(false, "umd").run()
      val Right(_) = eval.apply(command)

      assertLogContains(eval, command, expectedSuccessOutput)
    }

    test("run { no split per module / no module }") {
      val eval = testEval()

      val command = module.foo(false, "no").run()
      val Right(_) = eval.apply(command)

      assertLogContains(eval, command, expectedSuccessOutput)
    }

    // endregion
  }

  private def assertLogContains(eval: UnitTester, command: Command[Unit], text: String): Unit = {
    val log = EvaluatorPaths.resolveDestPaths(eval.outPath, command).log
    assert(os.read(log).contains(text))
  }

}
