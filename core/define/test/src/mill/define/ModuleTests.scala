package mill.define

import mill.util.TestGraphs
import utest.*

/**
 * Basic tests making sure the toString, millSourcePath, and
 * other things in a Module hierarchy are properly set up
 */
object ModuleTests extends TestSuite {

  val tests = Tests {

    object graphs extends TestGraphs()
    import graphs._
    import TestGraphs._

    test("singleton") {
      singleton.toString ==> ""
      singleton.single.toString ==> "single"
    }
    test("nested") {
      nestedModule.toString ==> ""
      nestedModule.moduleSegments ==> Segments()
      nestedModule.nested.toString ==> "nested"
      nestedModule.nested.modulePath.relativeTo(nestedModule.modulePath) ==>
        os.sub / "nested"
      nestedModule.nested.moduleSegments ==> Segments.labels("nested")
      nestedModule.nested.single.toString ==> "nested.single"
    }
    test("cross") {
      val base = nestedCrosses.modulePath
      nestedCrosses.toString ==> ""
      nestedCrosses.cross.toString ==> "cross"
      nestedCrosses.cross.moduleSegments ==> Segments.labels("cross")
      nestedCrosses.cross.modulePath.relativeTo(base) ==>
        os.sub / "cross"

      nestedCrosses.cross("210").toString ==> "cross[210]"
      nestedCrosses.cross("210").moduleSegments ==>
        Segments(List(Segment.Label("cross"), Segment.Cross(Seq("210"))))
      nestedCrosses.cross("210").modulePath.relativeTo(base) ==>
        os.sub / "cross"

      nestedCrosses.cross("210").cross2.toString ==> "cross[210].cross2"
      nestedCrosses.cross("210").cross2.moduleSegments ==>
        Segments(List(Segment.Label("cross"), Segment.Cross(Seq("210")), Segment.Label("cross2")))
      nestedCrosses.cross("210").cross2.modulePath.relativeTo(base) ==>
        os.sub / "cross/cross2"
      nestedCrosses.cross("210").cross2.modulePath.relativeTo(base) ==>
        os.sub / "cross" / "cross2"

      nestedCrosses.cross("210").cross2("js").toString ==> "cross[210].cross2[js]"
      nestedCrosses.cross("210").cross2("js").moduleSegments ==>
        Segments(List(
          Segment.Label("cross"),
          Segment.Cross(Seq("210")),
          Segment.Label("cross2"),
          Segment.Cross(Seq("js"))
        ))
      nestedCrosses.cross("210").cross2("js").modulePath.relativeTo(base) ==>
        os.sub / "cross" / "cross2"

    }
  }
}
