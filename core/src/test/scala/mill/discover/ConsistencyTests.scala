package mill.discover


import mill.discover.Mirror.Segment.Label
import mill.util.TestGraphs
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

      val expected = List(
        List(Label("down")),
        List(Label("right")),
        List(Label("left")),
        List(Label("up"))
      )

      'diamond - {
        val inconsistent = Discovered.consistencyCheck(
          diamond,
          Discovered[diamond.type]
        )

        assert(inconsistent == Nil)
      }
      'anonDiamond - {
        val inconsistent = Discovered.consistencyCheck(
          anonDiamond,
          Discovered[anonDiamond.type]
        )

        assert(inconsistent == Nil)
      }

      'borkedCachedDiamond2 - {
        val inconsistent = Discovered.consistencyCheck(
          borkedCachedDiamond2,
          Discovered[borkedCachedDiamond2.type]
        )
        assert(inconsistent == expected)
      }
      'borkedCachedDiamond3 - {
        val inconsistent = Discovered.consistencyCheck(
          borkedCachedDiamond3,
          Discovered[borkedCachedDiamond3.type]
        )
        assert(inconsistent == expected)
      }
    }

  }
}
