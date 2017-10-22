package forge

import utest._
import Target.noop
import java.nio.{file => jnio}
object ForgeTests extends TestSuite{
  val evaluator = new Evaluator(
    jnio.Paths.get("target/workspace"),
    implicitly
  )
  object Singleton {
    val single = noop()
  }
  object Pair {
    val up = noop()
    val down = noop(up)
  }
  object Diamond{
    val up = noop()
    val left = noop(up)
    val right = noop(up)
    val down = noop(left, right)
  }
  object AnonymousDiamond{
    val up = noop()
    val down = noop(noop(up), noop(up))
  }
  val tests = Tests{
    'topoSortedTransitiveTargets - {
      def check(targets: Seq[Target[_]], expected: Seq[Target[_]]) = {
        val result = evaluator.topoSortedTransitiveTargets(targets)
        assert(result == expected)
      }
      'singleton - check(
        targets = Seq(Singleton.single),
        expected = Seq(Singleton.single)
      )
      'pair - check(
        targets = Seq(Pair.down),
        expected = Seq(Pair.up, Pair.down)
      )
      'diamond - check(
        targets = Seq(Diamond.down),
        expected = Seq(Diamond.up, Diamond.right, Diamond.left, Diamond.down)
      )
      'anonDiamond - check(
        targets = Seq(Diamond.down),
        expected = Seq(Diamond.up, Diamond.down.inputs(1), Diamond.down.inputs(0), Diamond.down)
      )
    }

//    'full - {
//      val sourceRoot = Target.path(jnio.Paths.get("src/test/resources/example/src"))
//      val resourceRoot = Target.path(jnio.Paths.get("src/test/resources/example/resources"))
//      val allSources = list(sourceRoot)
//      val classFiles = compileAll(allSources)
//      val jar = jarUp(resourceRoot, classFiles)
//      Evaluator.apply(jar, jnio.Paths.get("target/workspace"))
//    }
  }
}
