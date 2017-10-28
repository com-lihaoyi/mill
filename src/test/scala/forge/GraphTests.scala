package forge

import utest._
import Target.test
import java.nio.{file => jnio}

object GraphTests extends TestSuite{

  val tests = Tests{


    val graphs = new TestUtil.TestGraphs()
    import graphs._

    'discovery{
      class CanNest{
        val single = T{ test() }
        val invisible: Any = T{ test() }
      }
      object outer {
        val single = T{ test() }
        val invisible: Any = T{ test() }
        object nested{
          val single = T{ test() }
          val invisible: Any = T{ test() }

        }
        val classInstance = new CanNest

      }
      val discovered = Discovered[outer.type].apply(outer)
      val expected = Seq(
        (List("classInstance", "single"), outer.classInstance.single),
        (List("nested", "single"), outer.nested.single),
        (List("single"), outer.single)
      )
      assert(discovered == expected)
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
      def check[T: Discovered](base: T,
                               target: Target.Test,
                               expected: OSet[(OSet[Target.Test], Int)]) = {

        val grouped = Evaluator.groupAroundNamedTargets(
          Evaluator.topoSortedTransitiveTargets(OSet(target))
        )
        TestUtil.checkTopological(grouped.flatMap(_.items))
        for(((expectedPresent, expectedSize), i) <- expected.items.zipWithIndex){
          val grouping = grouped.items(i)
          assert(
            grouping.size == expectedSize,
            expectedPresent.forall(grouping.contains)
          )
        }
      }
      'singleton - check(
        singleton,
        singleton.single,
        OSet(OSet(singleton.single) -> 1)
      )
      'pair - check(
        pair,
        pair.down,
        OSet(OSet(pair.up) -> 1, OSet(pair.down) -> 1)
      )
      'anonTriple - check(
        anonTriple,
        anonTriple.down,
        OSet(OSet(anonTriple.up) -> 1, OSet(anonTriple.down) -> 2)
      )
      'diamond - check(
        diamond,
        diamond.down,
        OSet(
          OSet(diamond.up) -> 1,
          OSet(diamond.left) -> 1,
          OSet(diamond.right) -> 1,
          OSet(diamond.down) -> 1
        )
      )
      'anonDiamond - check(
        anonDiamond,
        anonDiamond.down,
        OSet(
          OSet(anonDiamond.up) -> 1,
          OSet(anonDiamond.down) -> 3
        )
      )
      'bigSingleTerminal - check(
        bigSingleTerminal,
        bigSingleTerminal.j,
        OSet(
          OSet(bigSingleTerminal.a) -> 3,
          OSet(bigSingleTerminal.b) -> 2,
          OSet(bigSingleTerminal.e) -> 9,
          OSet(bigSingleTerminal.i) -> 6,
          OSet(bigSingleTerminal.f) -> 4,
          OSet(bigSingleTerminal.j) -> 4
        )
      )
    }

    'labeling - {

      def check[T: Discovered](base: T, t: Target[_], relPath: Option[String]) = {


        val names: Seq[(Target[_], Seq[String])] =
          implicitly[Discovered[T]].apply(base).map(_.swap)
        val nameMap = names.toMap

        val targetLabel = nameMap.get(t).map(_.mkString("."))
        assert(targetLabel == relPath)
      }
      'singleton - check(singleton, singleton.single, Some("single"))
      'pair - {
        check(pair, pair.up, Some("up"))
        check(pair, pair.down, Some("down"))
      }

      'anonTriple - {
        check(anonTriple, anonTriple.up, Some("up"))
        check(anonTriple, anonTriple.down.inputs(0), None)
        check(anonTriple, anonTriple.down, Some("down"))
      }

      'diamond - {
        check(diamond, diamond.up, Some("up"))
        check(diamond, diamond.left, Some("left"))
        check(diamond, diamond.right, Some("right"))
        check(diamond, diamond.down, Some("down"))
      }

      'anonDiamond - {
        check(anonDiamond, anonDiamond.up, Some("up"))
        check(anonDiamond, anonDiamond.down.inputs(0), None)
        check(anonDiamond, anonDiamond.down.inputs(1), None)
        check(anonDiamond, anonDiamond.down, Some("down"))
      }

    }

  }
}
