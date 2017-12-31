package acyclic

import utest._
import TestUtils.{make, makeFail}
import scala.tools.nsc.util.ScalaClassLoader.URLClassLoader
import acyclic.plugin.Value.{Pkg, File}
import scala.collection.SortedSet
import acyclic.file

object CycleTests extends TestSuite{

  def tests = TestSuite{
    'fail{
      'simple-makeFail("fail/simple")(Seq(
        File("B.scala") -> SortedSet(4, 5),
        File("A.scala") -> SortedSet(6)
      ))

      'indirect-makeFail("fail/indirect")(Seq(
        File("A.scala") -> SortedSet(6),
        File("B.scala") -> SortedSet(3),
        File("C.scala") -> SortedSet(4)
      ))
      'cyclicgraph-makeFail("fail/cyclicgraph")(
        Seq(
          File("A.scala") -> SortedSet(5),
          File("E.scala") -> SortedSet(6),
          File("D.scala") -> SortedSet(6),
          File("C.scala") -> SortedSet(4, 5)
        )
      )
      'cyclicpackage-makeFail("fail/cyclicpackage")(
        Seq(
          Pkg("fail.cyclicpackage.b") -> SortedSet(5),
          Pkg("fail.cyclicpackage.a") -> SortedSet(5)
        )
      )
      'halfpackagecycle-makeFail("fail/halfpackagecycle")(Seq(
        File("B.scala") -> SortedSet(3),
        File("A.scala") -> SortedSet(4),
        Pkg("fail.halfpackagecycle.c") -> SortedSet(5)
      ))
    }
    'success{
      'simple-make("success/simple")
      'ignorejava-make("success/java")
      'cyclicunmarked-make("success/cyclicunmarked")
      'dag-make("success/dag")
      'pkg{
        "single" - make("success/pkg/single")
        "mutualcyclic" - make("success/pkg/mutualcyclic")
        "halfacyclic" - make("success/pkg/halfacyclic")
        "innercycle" - make("success/pkg/innercycle")
      }
    }
    'self-make("../../main/scala", extraIncludes = Nil)
    'force{
      'fail-makeFail("force/simple", force = true)(Seq(
        File("B.scala") -> SortedSet(4, 5),
        File("A.scala") -> SortedSet(6)
      ))
      'pass-make("force/simple")
      'skip-make("force/skip", force = true)
    }
  }
}


