package mill.discover

import mill.define.Task
import mill.util.TestGraphs
import utest._
import mill.discover.Mirror.Segment.Label
object LabelingTests extends TestSuite{

  val tests = Tests{
    val graphs = new TestGraphs()
    import graphs._

    'labeling - {

      def check[T: Discovered](base: T, t: Task[_], relPath: Option[String]) = {


        val names: Seq[(Task[_], Seq[Mirror.Segment])] = Discovered.mapping(base).mapValues(_.segments).toSeq
        val nameMap = names.toMap

        val targetLabel = nameMap.get(t).map(_.map{case Label(v) => v}.mkString("."))
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
