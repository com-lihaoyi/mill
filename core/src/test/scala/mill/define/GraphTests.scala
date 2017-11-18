package mill.define

import mill.discover.Discovered
import mill.eval.Evaluator
import mill.util.{OSet, TestGraphs, TestUtil}
import utest._

object GraphTests extends TestSuite{

  val tests = Tests{


    val graphs = new TestGraphs()
    import graphs._

    'topoSortedTransitiveTargets - {
      def check(targets: OSet[Task[_]], expected: OSet[Task[_]]) = {
        val result = Evaluator.topoSorted(Evaluator.transitiveTargets(targets)).values
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
      'defCachedDiamond - check(
        targets = OSet(defCachedDiamond.down),
        expected = OSet(
          defCachedDiamond.up.inputs(0),
          defCachedDiamond.up,
          defCachedDiamond.down.inputs(0).inputs(0).inputs(0),
          defCachedDiamond.down.inputs(0).inputs(0),
          defCachedDiamond.down.inputs(0).inputs(1).inputs(0),
          defCachedDiamond.down.inputs(0).inputs(1),
          defCachedDiamond.down.inputs(0),
          defCachedDiamond.down
        )
      )
      'bigSingleTerminal - {
        val result = Evaluator.topoSorted(Evaluator.transitiveTargets(OSet(bigSingleTerminal.j))).values
        TestUtil.checkTopological(result)
        assert(result.size == 28)
      }
    }

    'groupAroundNamedTargets - {
      def check[T: Discovered, R <: Target[Int]](base: T,
                                                 target: R,
                                                 expected: OSet[(R, Int)]) = {

        val mapping = Discovered.mapping(base)
        val topoSorted = Evaluator.topoSorted(Evaluator.transitiveTargets(OSet(target)))

        val grouped = Evaluator.groupAroundImportantTargets(topoSorted) {
          case t: Target[_] if mapping.contains(t) => t
        }
        val flattened = OSet.from(grouped.values().flatMap(_.items))

        TestUtil.checkTopological(flattened)
        for((terminal, expectedSize) <- expected){
          val grouping = grouped.lookupKey(terminal)
          assert(
            grouping.size == expectedSize,
            grouping.flatMap(_.asTarget: Option[Target[_]]).filter(mapping.contains) == OSet(terminal)
          )
        }
      }
      'singleton - check(
        singleton,
        singleton.single,
        OSet(singleton.single -> 1)
      )
      'pair - check(
        pair,
        pair.down,
        OSet(pair.up -> 1, pair.down -> 1)
      )
      'anonTriple - check(
        anonTriple,
        anonTriple.down,
        OSet(anonTriple.up -> 1, anonTriple.down -> 2)
      )
      'diamond - check(
        diamond,
        diamond.down,
        OSet(
          diamond.up -> 1,
          diamond.left -> 1,
          diamond.right -> 1,
          diamond.down -> 1
        )
      )

      'defCachedDiamond - check(
        defCachedDiamond,
        defCachedDiamond.down,
        OSet(
          defCachedDiamond.up -> 2,
          defCachedDiamond.left -> 2,
          defCachedDiamond.right -> 2,
          defCachedDiamond.down -> 2
        )
      )

      'anonDiamond - check(
        anonDiamond,
        anonDiamond.down,
        OSet(
          anonDiamond.up -> 1,
          anonDiamond.down -> 3
        )
      )
      'bigSingleTerminal - check(
        bigSingleTerminal,
        bigSingleTerminal.j,
        OSet(
          bigSingleTerminal.a -> 3,
          bigSingleTerminal.b -> 2,
          bigSingleTerminal.e -> 9,
          bigSingleTerminal.i -> 6,
          bigSingleTerminal.f -> 4,
          bigSingleTerminal.j -> 4
        )
      )
    }


  }
}
