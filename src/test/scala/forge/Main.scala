package forge
import java.io.FileOutputStream
import java.util.jar.JarEntry

import collection.JavaConverters._
import ammonite.ops._
import forge.util.{OSet, PathRef}
object Main{
  val sourceRoot = Target.path(pwd / 'src / 'test / 'resources / 'example / 'src)
  val resourceRoot = Target.path(pwd / 'src / 'test / 'resources / 'example / 'resources)
  val allSources = list(sourceRoot)
  val classFiles = compileAll(allSources)
  val jar = jarUp(resourceRoot, classFiles)

  def main(args: Array[String]): Unit = {
    val mapping = Discovered.mapping(Main)
    val evaluator = new Evaluator(pwd / 'target / 'workspace / 'main, mapping)
    val res = evaluator.evaluate(OSet(jar))
    println(res.evaluated.collect(mapping))
  }
  def compileAll(sources: Target[Seq[PathRef]])  = {
    new Target.Subprocess(
      Seq(sources),
      args =>
        Seq("javac") ++
        args[Seq[PathRef]](0).map(_.path.toString) ++
        Seq("-d", args.dest.toString)
    ).map(_.dest)
  }

  def list(root: Target[PathRef]): Target[Seq[PathRef]] = {
    root.map(x => ls.rec(x.path).map(PathRef(_)))
  }
  case class jarUp(roots: Target[PathRef]*) extends Target[PathRef]{

    val inputs = roots
    def evaluate(args: Args): PathRef = {

      val output = new java.util.jar.JarOutputStream(new FileOutputStream(args.dest.toIO))
      for{
        root0 <- args.args
        root = root0.asInstanceOf[PathRef]

        path <- ls.rec(root.path)
        if path.isFile
      }{
        val relative = path.relativeTo(root.path)
        output.putNextEntry(new JarEntry(relative.toString))
        output.write(read.bytes(path))
      }
      output.close()
      PathRef(args.dest)
    }


  }

}
