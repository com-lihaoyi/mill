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
        val single = test()
        val invisible: Any = test()
      }
      object outer {
        val single = test()
        val invisible: Any = test()
        object nested{
          val single = test()
          val invisible: Any = test()

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

        val mapping: Map[Target[_], Seq[String]] = {
          implicitly[Discovered[T]].apply(base).map(_.swap).toMap
        }
        val grouped = Evaluator.groupAroundNamedTargets(
          Evaluator.topoSortedTransitiveTargets(OSet(target)),
          mapping
        )
        TestUtil.checkTopological(grouped.flatMap(_.items))
        for(((expectedPresent, expectedSize), i) <- expected.items.zipWithIndex){
          val grouping = grouped.items(i)
          assert(
            grouping.size == expectedSize,
            grouping.filter(mapping.contains) == expectedPresent
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
