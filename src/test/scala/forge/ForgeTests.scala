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
//    'singleton - {
//      evaluator.apply(Singleton.single)
//    }
    'pair - {
      evaluator.prepareTransitiveTargets(Seq(Pair.down))
    }
//    'diamond - {
//      evaluator.apply(Diamond.down)
//    }
//    'anonDiamond - {
//      evaluator.apply(AnonymousDiamond.down)
//    }
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
