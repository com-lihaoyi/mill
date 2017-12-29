package mill.discover

import mill.define.Task
import mill.util.TestGraphs
import utest._
import Discovered.mapping
import mill.discover.Mirror.Segment.Label
object LabelingTests extends TestSuite{

  val tests = Tests{
    val graphs = new TestGraphs()
    import graphs._

    'labeling - {

      def check(mapping: Discovered.Mapping[_], t: Task[_], relPath: Option[String]) = {


        val names: Seq[(Task[_], Seq[Mirror.Segment])] = mapping.targets.toSeq
        val nameMap = names.toMap

        val targetLabel = nameMap.get(t).map(_.map{case Label(v) => v}.mkString("."))
        assert(targetLabel == relPath)
      }
      'singleton - check(mapping(singleton), singleton.single, Some("single"))
      'pair - {
        check(mapping(pair), pair.up, Some("up"))
        check(mapping(pair), pair.down, Some("down"))
      }

      'anonTriple - {
        check(mapping(anonTriple), anonTriple.up, Some("up"))
        check(mapping(anonTriple), anonTriple.down.inputs(0), None)
        check(mapping(anonTriple), anonTriple.down, Some("down"))
      }

      'diamond - {
        check(mapping(diamond), diamond.up, Some("up"))
        check(mapping(diamond), diamond.left, Some("left"))
        check(mapping(diamond), diamond.right, Some("right"))
        check(mapping(diamond), diamond.down, Some("down"))
      }

      'anonDiamond - {
        check(mapping(anonDiamond), anonDiamond.up, Some("up"))
        check(mapping(anonDiamond), anonDiamond.down.inputs(0), None)
        check(mapping(anonDiamond), anonDiamond.down.inputs(1), None)
        check(mapping(anonDiamond), anonDiamond.down, Some("down"))
      }

    }

  }
}
