package forge

import utest._
import Target.test
import java.nio.{file => jnio}

object GraphTests extends TestSuite{

  val tests = Tests{


    val (singleton, pair, anonTriple, diamond, anonDiamond, bigSingleTerminal) = TestUtil.makeGraphs()


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
      def check(targets: OSet[Target[_]], expected: OSet[Target[_]]) = {
        val result = Evaluator.topoSortedTransitiveTargets(targets).values
        TestUtil.checkTopological(result)
        assert(result == expected)
      }

      'singleton - check(
        targets = OSet(singleton.single),
        expected = OSet(singleton.single)
      )
      'pair - check(
        targets = OSet(pair.down),
        expected = OSet(pair.up, pair.down)
      )
      'anonTriple - check(
        targets = OSet(anonTriple.down),
        expected = OSet(anonTriple.up, anonTriple.down.inputs(0), anonTriple.down)
      )
      'diamond - check(
        targets = OSet(diamond.down),
        expected = OSet(diamond.up, diamond.left, diamond.right, diamond.down)
      )
      'anonDiamond - check(
        targets = OSet(diamond.down),
        expected = OSet(
          diamond.up,
          diamond.down.inputs(0),
          diamond.down.inputs(1),
          diamond.down
        )
      )
      'bigSingleTerminal - {
        val result = Evaluator.topoSortedTransitiveTargets(OSet(bigSingleTerminal.j)).values
        TestUtil.checkTopological(result)
        assert(result.size == 28)
      }
    }

    'groupAroundNamedTargets - {
      def check(target: Target[_], expected: OSet[OSet[String]]) = {
        val grouped = Evaluator.groupAroundNamedTargets(
          Evaluator.topoSortedTransitiveTargets(OSet(target))
        )
        TestUtil.checkTopological(grouped.flatMap(_.items))
        val stringified = grouped.map(_.map(_.toString))
        assert(stringified == expected)
      }
      'singleton - check(
        singleton.single,
        OSet(OSet("single"))
      )
      'pair - check(
        pair.down,
        OSet(OSet("up"), OSet("down"))
      )
      'anonTriple - check(
        anonTriple.down,
        OSet(OSet("up"), OSet("down1", "down"))
      )
      'diamond - check(
        diamond.down,
        OSet(OSet("up"), OSet("left"), OSet("right"), OSet("down"))
      )
      'anonDiamond - check(
        anonDiamond.down,
        OSet(
          OSet("up"),
          OSet("down2", "down1", "down")
        )
      )
      'bigSingleTerminal - check(
        bigSingleTerminal.j,
        OSet(
          OSet("i1"),
          OSet("e4"),
          OSet("a1"),
          OSet("a2"),
          OSet("a"),
          OSet("b1"),
          OSet("b"),
          OSet("e5", "e2", "e8", "e1", "e7", "e6", "e3", "e"),
          OSet("i2", "i5", "i4", "i3", "i"),
          OSet("f2"),
          OSet("f3", "f1", "f"),
          OSet("j3", "j2", "j1", "j")
        )
      )
    }

    'labeling - {

      def check(t: Target[_], relPath: String) = {
        val targetLabel = t.defCtx.label.split(' ').last

        assert(targetLabel == relPath)
      }
      'singleton - check(singleton.single, "singleton.single")
      'pair - {
        check(pair.up, "pair.up")
        check(pair.down, "pair.down")
      }

      'anonTriple - {
        check(anonTriple.up, "anonTriple.up")
        check(anonTriple.down.inputs(0), "anonTriple.down1")
        check(anonTriple.down, "anonTriple.down")
      }

      'diamond - {
        check(diamond.up, "diamond.up")
        check(diamond.left, "diamond.left")
        check(diamond.right, "diamond.right")
        check(diamond.down, "diamond.down")
      }

      'anonDiamond - {
        check(anonDiamond.up, "anonDiamond.up")
        check(anonDiamond.down.inputs(0), "anonDiamond.down1")
        check(anonDiamond.down.inputs(1), "anonDiamond.down2")
        check(anonDiamond.down, "anonDiamond.down")
      }

    }

  }
}
