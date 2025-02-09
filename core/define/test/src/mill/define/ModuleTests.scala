package mill.define

import mill.define.{Discover, DynamicModule, ModuleRef, NamedTask, TaskModule}
import mill.testkit.TestBaseModule
import mill.util.TestGraphs
import mill.util.TestGraphs.*
import mill.{Cross, Module, Task}
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

    test("singleton"){
      singleton.toString ==> ""
      singleton.single.toString ==> "single"
    }
    test("nested"){
      nestedModule.toString ==> ""
      nestedModule.millModuleSegments ==> Segments()
      nestedModule.nested.toString ==> "nested"
      nestedModule.nested.millSourcePath.relativeTo(nestedModule.millSourcePath) ==>
        os.sub / "nested"
      nestedModule.nested.millModuleSegments ==> Segments.labels("nested")
      nestedModule.nested.single.toString ==> "nested.single"
    }
    test("cross"){
      nestedCrosses.toString ==> ""
      nestedCrosses.cross.toString ==> "cross"
      nestedCrosses.cross.millModuleSegments ==> Segments.labels("cross")
      nestedCrosses.cross.millSourcePath.relativeTo(nestedCrosses.millSourcePath) ==>
        os.sub / "cross"

      nestedCrosses.cross("210").toString ==> "cross[210]"
      nestedCrosses.cross("210").millModuleSegments ==>
        Segments(List(Segment.Label("cross"), Segment.Cross(Seq("210"))))
      nestedCrosses.cross("210").millSourcePath.relativeTo(nestedCrosses.millSourcePath) ==>
        os.sub / "cross"

      nestedCrosses.cross("210").cross2.toString ==> "cross[210].cross2"
      nestedCrosses.cross("210").cross2.millModuleSegments ==>
        Segments(List(Segment.Label("cross"), Segment.Cross(Seq("210")), Segment.Label("cross2")))


      nestedCrosses.cross("210").cross2.millSourcePath.relativeTo(nestedCrosses.millSourcePath) ==> os.sub / "cross" / "cross2"
      nestedCrosses.cross("210").cross2("js").toString ==> "cross[210].cross2[js]"
    }
  }
}
