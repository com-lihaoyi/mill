package mill.discover

import mill.{Module, T}
import mill.define.Cross
import mill.discover.Mirror.{LabelledTarget, Segment}
import mill.discover.Mirror.Segment.Label
import utest._

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
        assert(outer.crossed(k).scalaVersion == k.head)
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
        val suffix = outer.crossed(k).suffix
        val expected = k.map(_.toString).filter(_.nonEmpty).map("_"+_).mkString
        assert(suffix == expected)
      }
    }
    'crossTargetDiscovery - {
      object outer{
        val crossed =
          for(n <- Cross("2.10.6", "2.11.8", "2.12.4"))
          yield new Module{ def scalaVersion = T{ n } }
      }
      val discovered = Discovered[outer.type]

      val segments = discovered.targets(outer).map(_.segments)
      val expectedSegments = List(
        List(Label("crossed"), Segment.Cross(List("2.10.6")), Label("scalaVersion")),
        List(Label("crossed"), Segment.Cross(List("2.11.8")), Label("scalaVersion")),
        List(Label("crossed"), Segment.Cross(List("2.12.4")), Label("scalaVersion"))
      )
      assert(segments == expectedSegments)
      val targets = discovered.targets(outer).map(_.target)
      val expected = List(
        outer.crossed(List("2.10.6")).scalaVersion,
        outer.crossed(List("2.11.8")).scalaVersion,
        outer.crossed(List("2.12.4")).scalaVersion
      )
      assert(targets == expected)
    }

    'doubleCrossTargetDiscovery - {
      object outer{
        val crossed =
          for{
            n <- Cross("2.11.8", "2.12.4")
            platform <- Cross("sjs0.6", "native0.3")
          } yield new Module{ def suffix = T{ n + "_" + platform } }
      }
      val discovered = Discovered[outer.type]
      val targets = discovered.targets(outer).map(_.target)
      
      val expected = List(
        outer.crossed(List("sjs0.6", "2.11.8")).suffix,
        outer.crossed(List("native0.3", "2.11.8")).suffix,
        outer.crossed(List("sjs0.6", "2.12.4")).suffix,
        outer.crossed(List("native0.3", "2.12.4")).suffix
      )
      assert(targets == expected)
    }
  }
}
