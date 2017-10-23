package forge

import utest._
import Target.test
import java.nio.{file => jnio}
object ForgeTests extends TestSuite{

  val tests = Tests{
    implicit def fakeStaticContext = DefCtx.StaticContext(true)
    val evaluator = new Evaluator(jnio.Paths.get("target/workspace"), implicitly)
    object Singleton {
      val single = test()
    }
    object Pair {
      val up = test()
      val down = test(up)
    }
    object AnonTriple{
      val up = test()
      val down = test(test(up))
    }
    object Diamond{
      val up = test()
      val left = test(up)
      val right = test(up)
      val down = test(left, right)
    }
    object AnonDiamond{
      val up = test()
      val down = test(test(up), test(up))
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

      'evaluate - {
        def check(targets: Seq[Target[_]],
                  values: Seq[Any],
                  evaluated: Seq[Target[_]]) = {
          val Evaluator.Results(returnedValues, returnedEvaluated) = evaluator.evaluate(targets)
          assert(
            returnedValues == values,
            returnedEvaluated == evaluated
          )

        }
        'singleton - {
          import Singleton._
          // First time the target is evaluated
          check(Seq(single), values = Seq(0), evaluated = Seq(single))
          // Second time the value is already cached, so no evaluation needed
          check(Seq(single), values = Seq(0), evaluated = Seq())
          single.counter += 1
          // After incrementing the counter, it forces re-evaluation
          check(Seq(single), values = Seq(1), evaluated = Seq(single))
          // Then it's cached again
          check(Seq(single), values = Seq(1), evaluated = Seq())
        }
        'pair - {
          import Pair._
          check(Seq(down), values = Seq(0), evaluated = Seq(up, down))
          check(Seq(down), values = Seq(0), evaluated = Seq())

          down.counter += 1
          check(Seq(down), values = Seq(1), evaluated = Seq(down))
          check(Seq(down), values = Seq(1), evaluated = Seq())

          up.counter += 1
          check(Seq(down), values = Seq(2), evaluated = Seq(up, down))
          check(Seq(down), values = Seq(2), evaluated = Seq())
        }
        'anonTriple - {
          import AnonTriple._
          val middle = down.inputs(0)
          check(Seq(down), values = Seq(0), evaluated = Seq(up, middle, down))
          check(Seq(down), values = Seq(0), evaluated = Seq())

          down.counter += 1
          check(Seq(down), values = Seq(1), evaluated = Seq(middle, down))
          check(Seq(down), values = Seq(1), evaluated = Seq())

          up.counter += 1
          check(Seq(down), values = Seq(2), evaluated = Seq(up, middle, down))
          check(Seq(down), values = Seq(2), evaluated = Seq())
        }
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
