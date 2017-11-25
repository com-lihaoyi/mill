package mill.discover

import mill.{Module, T}
import mill.define.Cross
import mill.discover.Mirror.{LabelledTarget, Segment}
import mill.discover.Mirror.Segment.Label
import mill.util.TestUtil.test
import utest._
import mill.util.TestGraphs._
object CrossModuleTests extends TestSuite{

  val tests = Tests{

    'cross - {
      object outer{
        val crossed =
          for(n <- Cross("2.10.6", "2.11.8", "2.12.4"))
          yield new Module{
            def scalaVersion = n
          }
      }

      val discovered = Discovered[outer.type]

      val Some((gen, innerMirror)) = discovered
        .mirror
        .children
        .head._2
        .asInstanceOf[Mirror[outer.type, outer.crossed.type]]
        .crossChildren

      val keys = gen(outer.crossed)
      assert(keys == List(List("2.10.6"), List("2.11.8"), List("2.12.4")))
      for(k <- keys){
        assert(outer.crossed(k:_*).scalaVersion == k.head)
      }
    }
    'doubleCross - {
      object outer{
        val crossed =
          for{
            platform <- Cross("", "sjs0.6", "native0.3")
            scalaVersion <- Cross("2.10.6", "2.11.8", "2.12.4")
            if !(platform == "native0.3" && scalaVersion == "2.10.6")
          } yield new Module{
            def suffix = Seq(scalaVersion, platform).filter(_.nonEmpty).map("_"+_).mkString
          }
      }

      val Some((gen, innerMirror)) = Discovered[outer.type]
        .mirror
        .children
        .head._2
        .asInstanceOf[Mirror[outer.type, outer.crossed.type]]
        .crossChildren

      val keys = gen(outer.crossed)
      val expectedKeys = List(
        List("2.10.6", ""),
        List("2.11.8", ""),
        List("2.12.4", ""),
        List("2.10.6", "sjs0.6"),
        List("2.11.8", "sjs0.6"),
        List("2.12.4", "sjs0.6"),
        List("2.11.8", "native0.3"),
        List("2.12.4", "native0.3"),
      )

      assert(keys == expectedKeys)
      for(k <- keys){
        val suffix = outer.crossed(k:_*).suffix
        val expected = k.map(_.toString).filter(_.nonEmpty).map("_"+_).mkString
        assert(suffix == expected)
      }
    }
    'crossTargetDiscovery - {

      val discovered = Discovered[singleCross.type].targets(singleCross)

      val segments = discovered.map(_.segments)
      val expectedSegments = List(
        List(Label("cross"), Segment.Cross(List("210")), Label("suffix")),
        List(Label("cross"), Segment.Cross(List("211")), Label("suffix")),
        List(Label("cross"), Segment.Cross(List("212")), Label("suffix"))
      )
      assert(segments == expectedSegments)
      val targets = discovered.map(_.target)
      val expected = List(
        singleCross.cross("210").suffix,
        singleCross.cross("211").suffix,
        singleCross.cross("212").suffix
      )
      assert(targets == expected)
    }

    'doubleCrossTargetDiscovery - {
      val discovered = Discovered[doubleCross.type]
      val targets = discovered.targets(doubleCross).map(_.target)

      val expected = List(
        doubleCross.cross("jvm", "210").suffix,
        doubleCross.cross("js", "210").suffix,
        doubleCross.cross("jvm", "211").suffix,
        doubleCross.cross("js", "211").suffix,
        doubleCross.cross("jvm", "212").suffix,
        doubleCross.cross("js", "212").suffix,
        doubleCross.cross("native", "212").suffix,

      )
      assert(targets == expected)
    }

    'nestedCrosses - {
      val discovered = Discovered[nestedCrosses.type].targets(nestedCrosses)
      assert(discovered.length == 9)
    }
  }
}

