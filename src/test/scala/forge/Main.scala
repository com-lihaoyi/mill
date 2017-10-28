package forge
import java.io.FileOutputStream
import java.nio.{file => jnio}
import java.util.jar.JarEntry
import collection.JavaConverters._
object Main{

  def main(args: Array[String]): Unit = {

    val sourceRoot = T{ Target.path(jnio.Paths.get("src/test/resources/example/src")) }
    val resourceRoot = T{ Target.path(jnio.Paths.get("src/test/resources/example/resources")) }
    val allSources = T{ list(sourceRoot) }
    val classFiles = T{ compileAll(allSources) }
    val jar = T{ jarUp(resourceRoot, classFiles) }

    val evaluator = new Evaluator(
      jnio.Paths.get("target/workspace"),
      DefCtx("forge.Main ", None)
    )
    evaluator.evaluate(OSet(jar))
  }
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
