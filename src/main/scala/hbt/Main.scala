package hbt
import java.nio.{file => jnio}
import Util._
object Main{
  def main(args: Array[String]): Unit = {
    val sourceRoot = Target.path(jnio.Paths.get("test/src"))
    val resourceRoot = Target.path(jnio.Paths.get("test/resources"))
    val allSources = list(sourceRoot)
    val classFiles = compileAll(allSources)
    val jar = jarUp(resourceRoot, classFiles)
    Hbt.evaluateTargetGraph(jar)
  }
}