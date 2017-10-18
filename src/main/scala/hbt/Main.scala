package hbt
import java.nio.{file => jnio}
import Util._
object Main{
  def main(args: Array[String]): Unit = {
    val sourceRoot = Target.Path(jnio.Paths.get("test/src"), "sourceRoot")
    val resourceRoot = Target.Path(jnio.Paths.get("test/resources"), "resourceRoot")
    val allSources = list(sourceRoot)
    val classFiles = compileAll(allSources)
    val jar = jarUp(resourceRoot, classFiles)
  }
}