package forge

import java.io.FileOutputStream
import java.nio.{file => jnio}
import java.util.jar.JarEntry

import sourcecode.Enclosing

import scala.collection.JavaConverters._
import scala.collection.mutable

class MultiBiMap[K, V](){
  private[this] val valueToKey = mutable.Map.empty[V, K]
  private[this] val keyToValues = mutable.Map.empty[K, List[V]]
  def containsValue(v: V) = valueToKey.contains(v)
  def lookupValue(v: V) = valueToKey(v)
  def lookupValueOpt(v: V) = valueToKey.get(v)
  def add(k: K, v: V): Unit = {
    valueToKey(v) = k
    keyToValues(k) = v :: keyToValues.getOrElse(k, Nil)
  }
  def removeAll(k: K): Seq[V] = {
    val vs = keyToValues(k)
    for(v <- vs){
      valueToKey.remove(v)
    }
    vs
  }
  def addAll(k: K, vs: Seq[V]): Unit = {
    for(v <- vs) valueToKey(v) = k
    keyToValues(k) = vs ++: keyToValues.getOrElse(k, Nil)
  }
}
object Util{
  def compileAll(sources: Target[Seq[jnio.Path]])
                (implicit defCtx: DefCtx) = {
    new Target.Subprocess(
      Seq(sources),
      args =>
        Seq("javac") ++
        args[Seq[jnio.Path]](0).map(_.toAbsolutePath.toString) ++
        Seq("-d", args.dest.toAbsolutePath.toString),
      defCtx
    ).map(_.dest)
  }

  def list(root: Target[jnio.Path])(implicit defCtx: DefCtx): Target[Seq[jnio.Path]] = {
    root.map(jnio.Files.list(_).iterator().asScala.toArray[jnio.Path])
  }
  case class jarUp(roots: Target[jnio.Path]*)(implicit val defCtx: DefCtx) extends Target[jnio.Path]{

    val inputs = roots
    def evaluate(args: Args): jnio.Path = {

      val output = new java.util.jar.JarOutputStream(new FileOutputStream(args.dest.toFile))
      for{
        root0 <- args.args
        root = root0.asInstanceOf[jnio.Path]

        path <- jnio.Files.walk(root).iterator().asScala
        if jnio.Files.isRegularFile(path)
      }{
        val relative = root.relativize(path)
        output.putNextEntry(new JarEntry(relative.toString))
        output.write(jnio.Files.readAllBytes(path))
      }
      output.close()
      args.dest
    }


  }


}