package forge

import utest._
import Target.test
import java.nio.{file => jnio}
object ForgeTests extends TestSuite{

  val tests = Tests{
    val baseCtx = DefCtx("forge.ForgeTests.tests ")
    val evaluator = new Evaluator(jnio.Paths.get("target/workspace"), baseCtx)
    object Singleton {
      val single = T{ test() }
    }
    object Pair {
      val up = T{ test() }
      val down = T{ test(up) }
    }

    object AnonTriple{
      val up = T{ test() }
      val down = T{ test(test(up)) }
    }
    object Diamond{
      val up = T{ test() }
      val left = T{ test(up) }
      val right = T{ test(up) }
      val down = T{ test(left, right) }
    }
    object AnonDiamond{
      val up = T{ test() }
      val down = T{ test(test(up), test(up)) }
    }


    'syntaxLimits - {
      // Make sure that we properly prohibit cases where a `test()` target can
      // be created more than once with the same `DefCtx`, while still allowing
      // cases where the `test()` target is created exactly one time, or even
      // zero-or-one times (since that's ok, as long as it's not more than once)

      'neg - {
        'nakedTest - {
          compileError("test()")
          ()
        }
        'notFunctionCall - {
          compileError("T{ 123 }")
          ()
        }
        'functionCallWithoutImplicit - {
          compileError("T{ println() }")
          ()
        }
        // Make sure the snippets without `test()`s compile, but the same snippets
        // *with* the `test()` calls do not (presumably due to the `@compileTimeOnly`
        // annotation)
        //
        // For some reason, `if(false)` isn't good enough because scalac constant
        // folds the conditional, eliminates the entire code block, and makes any
        // `@compileTimeOnly`s annotations disappear...


        'canEvaluateMoreThanOnce - {
          if (math.random() > 10) T{ Seq(1, 2).map(_ => ???); test() }
          compileError("T{ Seq(1, 2).map(_ => test()); test() }")

          if (math.random() > 10) T{ class Foo{ ??? }; test() }
          compileError("T{ class Foo{ test() }; test() }")

          if (math.random() > 10) T{ test({while(true){ }; ???}) }
          compileError("T{ test({while(true){ test() }; ???}) }")

          if (math.random() > 10) T{ do{ } while(true); test() }
          compileError("T{ do{ test() } while(true); test() }")

          if (math.random() > 10) T{ def foo() = ???; test() }
          compileError("T{ def foo() = test(); test() }")

          if (math.random() > 10) T{ None.getOrElse(???); test() }
          if (math.random() > 10) T{ None.contains(test()); test() }
          compileError("T{ None.getOrElse(test()); test() }")

          ()
        }
      }
      'pos - {
        T{ test({val x = test(); x}) }
        T{ test({lazy val x = test(); x}) }
        T { object foo {val x = test()}; test(foo.x) }
        T{ test({val x = if (math.random() > 0.5) test() else test(); x}) }

        ()
      }
    }


    'topoSortedTransitiveTargets - {
      def check(targets: Seq[Target[_]], expected: Seq[Target[_]]) = {
        val result = Evaluator.topoSortedTransitiveTargets(targets)
        assert(result == expected)
      }

      'singleton - check(
        targets = Seq(Singleton.single),
        expected = Seq(Singleton.single)
      )
      'pair - check(
        targets = Seq(Pair.down),
        expected = Seq(Pair.up, Pair.down)
      )
      'anonTriple - check(
        targets = Seq(AnonTriple.down),
        expected = Seq(AnonTriple.up, AnonTriple.down.inputs(0), AnonTriple.down)
      )
      'diamond - check(
        targets = Seq(Diamond.down),
        expected = Seq(Diamond.up, Diamond.left, Diamond.right, Diamond.down)
      )
      'anonDiamond - check(
        targets = Seq(Diamond.down),
        expected = Seq(
          Diamond.up,
          Diamond.down.inputs(0),
          Diamond.down.inputs(1),
          Diamond.down
        )
      )
    }
    'labeling - {

      def check(t: Target[_], relPath: String) = {
        val targetLabel = t.defCtx.label
        val expectedLabel = baseCtx.label + relPath
        assert(targetLabel == expectedLabel)
      }
      'singleton - check(Singleton.single, "Singleton.single")
      'pair - {
        check(Pair.up, "Pair.up")
        check(Pair.down, "Pair.down")
      }

      'anonTriple - {
        check(AnonTriple.up, "AnonTriple.up")
        check(AnonTriple.down.inputs(0), "AnonTriple.down1")
        check(AnonTriple.down, "AnonTriple.down")
      }

      'diamond - {
        check(Diamond.up, "Diamond.up")
        check(Diamond.left, "Diamond.left")
        check(Diamond.right, "Diamond.right")
        check(Diamond.down, "Diamond.down")
      }

      'anonDiamond - {
        check(AnonDiamond.up, "AnonDiamond.up")
        check(AnonDiamond.down.inputs(0), "AnonDiamond.down1")
        check(AnonDiamond.down.inputs(1), "AnonDiamond.down2")
        check(AnonDiamond.down, "AnonDiamond.down")
      }

    }
    'evaluate - {
      def check(targets: Seq[Target[_]],
                expectedValues: Seq[Any],
                expectedEvaluated: Seq[Target[_]]) = {
        val Evaluator.Results(returnedValues, returnedEvaluated) = evaluator.evaluate(targets)
        assert(
          returnedValues == expectedValues,
          returnedEvaluated == expectedEvaluated
        )

      }
      'singleton - {
        import Singleton._
        // First time the target is evaluated
        check(Seq(single), expectedValues = Seq(0), expectedEvaluated = Seq(single))
        // Second time the value is already cached, so no evaluation needed
        check(Seq(single), expectedValues = Seq(0), expectedEvaluated = Seq())
        single.counter += 1
        // After incrementing the counter, it forces re-evaluation
        check(Seq(single), expectedValues = Seq(1), expectedEvaluated = Seq(single))
        // Then it's cached again
        check(Seq(single), expectedValues = Seq(1), expectedEvaluated = Seq())
      }
      'pair - {
        import Pair._
        check(Seq(down), expectedValues = Seq(0), expectedEvaluated = Seq(up, down))
        check(Seq(down), expectedValues = Seq(0), expectedEvaluated = Seq())

        down.counter += 1
        check(Seq(down), expectedValues = Seq(1), expectedEvaluated = Seq(down))
        check(Seq(down), expectedValues = Seq(1), expectedEvaluated = Seq())

        up.counter += 1
        check(Seq(down), expectedValues = Seq(2), expectedEvaluated = Seq(up, down))
        check(Seq(down), expectedValues = Seq(2), expectedEvaluated = Seq())
      }
      'anonTriple - {
        import AnonTriple._
        val middle = down.inputs(0)
        check(Seq(down), expectedValues = Seq(0), expectedEvaluated = Seq(up, middle, down))
        check(Seq(down), expectedValues = Seq(0), expectedEvaluated = Seq())

        down.counter += 1
        check(Seq(down), expectedValues = Seq(1), expectedEvaluated = Seq(down))
        check(Seq(down), expectedValues = Seq(1), expectedEvaluated = Seq())

        up.counter += 1
        check(Seq(down), expectedValues = Seq(2), expectedEvaluated = Seq(up, middle, down))
        check(Seq(down), expectedValues = Seq(2), expectedEvaluated = Seq())
      }
    }


//    'full - {
//      val sourceRoot = Target.path(jnio.Paths.get("src/test/resources/example/src"))
//      val resourceRoot = Target.path(jnio.Paths.get("src/test/resources/example/resources"))
//      val allSources = list(sourceRoot)
//      val classFiles = compileAll(allSources)
//      val jar = jarUp(resourceRoot, classFiles)
//      Evaluator.apply(jar, jnio.Paths.get("target/workspace"))
//    }
  }
}
