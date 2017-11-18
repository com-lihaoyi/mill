package mill.discover

import utest._
import mill.Module
import mill.util.TestUtil.test
object DiscoveredTests extends TestSuite{

  val tests = Tests{

    'discovery{
      class CanNest extends Module{
        val single = test()
        val invisible: Any = test()
      }
      object outer {
        val single = test()
        val invisible: Any = test()
        object nested extends Module{
          val single = test()
          val invisible: Any = test()

        }
        val classInstance = new CanNest

      }

      val discovered = Discovered[outer.type]


      def flatten(h: Mirror[outer.type, _]): Seq[Any] = {
        h.node(outer) :: h.children.flatMap{case (label, c) => flatten(c)}
      }
      val flattenedHierarchy = flatten(discovered.mirror)

      val expectedHierarchy = Seq(
        outer,
        outer.classInstance,
        outer.nested,
      )
      assert(flattenedHierarchy == expectedHierarchy)

      val mapped = discovered.targets(outer).map(x => x.segments -> x.target)

      val expected = Seq(
        (List("classInstance", "single"), outer.classInstance.single),
        (List("nested", "single"), outer.nested.single),
        (List("single"), outer.single)
      )
      assert(mapped.toSet == expected.toSet)
    }
  }
}
