package hbt

import hbt.Util.{compileAll, jarUp, list}
import utest._
import java.nio.{file => jnio}
object HbtTests extends TestSuite{
  val tests = Tests{
    'simple - {
      val sourceRoot = Target.path(jnio.Paths.get("src/test/resources/example/src"))
      val resourceRoot = Target.path(jnio.Paths.get("src/test/resources/example/resources"))
      val allSources = list(sourceRoot)
      val classFiles = compileAll(allSources)
      val jar = jarUp(resourceRoot, classFiles)
      Evaluator.apply(jar, jnio.Paths.get("target/workspace"))
    }
  }
}
