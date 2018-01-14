package mill.discover


import mill.define.Segment.Label
import mill.define.Segments
import mill.util.{TestGraphs}
import mill.util.Strict.Agg
import utest._

object ConsistencyTests extends TestSuite{

  val tests = Tests{
    val graphs = new TestGraphs()
    import graphs._
    'failConsistencyChecks - {
      // Make sure these fail because `def`s without `Cacher` will re-evaluate
      // each time, returning different sets of targets.
      //
      // Maybe later we can convert them into compile errors somehow

      val expected = Agg(
        Segments(Label("down")),
        Segments(Label("right")),
        Segments(Label("left")),
        Segments(Label("up"))
      )

      'diamond - {
        val inconsistent = Discovered.consistencyCheck(
          Discovered.mapping(diamond)
        )

        assert(inconsistent == Agg())
      }

      'anonDiamond - {
        val inconsistent = Discovered.consistencyCheck(
          Discovered.mapping(anonDiamond)
        )

        assert(inconsistent == Agg())
      }

      'borkedCachedDiamond2 - {
        val inconsistent = Discovered.consistencyCheck(
          Discovered.mapping(borkedCachedDiamond2)
        )
        assert(inconsistent.toSet == expected.toSet)
      }
      'borkedCachedDiamond3 - {
        val inconsistent = Discovered.consistencyCheck(
          Discovered.mapping(borkedCachedDiamond3)
        )
        assert(inconsistent.toSet == expected.toSet)
      }
    }

  }
}
