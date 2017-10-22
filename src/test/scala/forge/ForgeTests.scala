package forge

import utest._
import Target.noop

object ForgeTests extends TestSuite{
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
    'singleton - {
//      Evaluator.labelTargets(Singleton.single)
    }
    'pair - {
//      Evaluator.labelTargets(Pair.up, Pair.down)
    }
    'diamond - {
//      Evaluator.labelTargets(
//        Diamond.up, Diamond.left, Diamond.right, Diamond.down
//      )
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
