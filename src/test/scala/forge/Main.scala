package forge
import java.io.FileOutputStream
import java.util.jar.JarEntry
import collection.JavaConverters._
import ammonite.ops._
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
  def compileAll(sources: Target[Seq[Path]])  = {
    new Target.Subprocess(
      Seq(sources),
      args =>
        Seq("javac") ++
        args[Seq[Path]](0).map(_.toString) ++
        Seq("-d", args.dest.toString)
    ).map(_.dest)
  }

  def list(root: Target[Path]): Target[Seq[Path]] = {
    root.map(ls.rec)
  }
  case class jarUp(roots: Target[Path]*) extends Target[Path]{

    val inputs = roots
    def evaluate(args: Args): Path = {

      val output = new java.util.jar.JarOutputStream(new FileOutputStream(args.dest.toIO))
      for{
        root0 <- args.args
        root = root0.asInstanceOf[Path]

        path <- ls.rec(root)
        if path.isFile
      }{
        val relative = path.relativeTo(root)
        output.putNextEntry(new JarEntry(relative.toString))
        output.write(read.bytes(path))
      }
      output.close()
      args.dest
    }


  }

}
