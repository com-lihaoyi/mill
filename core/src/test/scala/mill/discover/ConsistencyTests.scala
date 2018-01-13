package mill.discover


import mill.define.Segment.Label
import mill.define.Segments
import mill.util.{TestGraphs}
import mill.util.Strict.OSet
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

      val expected = OSet(
        Segments(Label("down")),
        Segments(Label("right")),
        Segments(Label("left")),
        Segments(Label("up"))
      )

      'diamond - {
        val inconsistent = Discovered.consistencyCheck(
          Discovered.mapping(diamond)
        )

        assert(inconsistent == OSet())
      }

      'anonDiamond - {
        val inconsistent = Discovered.consistencyCheck(
          Discovered.mapping(anonDiamond)
        )

        assert(inconsistent == OSet())
      }

      'borkedCachedDiamond2 - {
        val inconsistent = Discovered.consistencyCheck(
          Discovered.mapping(borkedCachedDiamond2)
        )
        assert(inconsistent == expected)
      }
      'borkedCachedDiamond3 - {
        val inconsistent = Discovered.consistencyCheck(
          Discovered.mapping(borkedCachedDiamond3)
        )
        assert(inconsistent == expected)
      }
    }

  }
}
