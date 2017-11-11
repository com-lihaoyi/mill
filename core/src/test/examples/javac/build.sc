import mill.define.Task
import mill.eval.PathRef

object Foo {

  import java.io.FileOutputStream
  import java.util.jar.JarEntry

  import ammonite.ops.{ls, pwd, read}
  import mill.discover.Discovered
  import mill.util.Args

  val workspacePath = pwd / 'target / 'workspace / 'javac
  val javacSrcPath = pwd / 'src / 'test / 'examples / 'javac
  val javacDestPath = workspacePath / 'src

  val sourceRootPath = javacDestPath / 'src
  val resourceRootPath = javacDestPath / 'resources

  // sourceRoot -> allSources -> classFiles
  //                                |
  //                                v
  //           resourceRoot ---->  jar
  val sourceRoot = Task.path(sourceRootPath)
  val resourceRoot = Task.path(resourceRootPath)
  val allSources = list(sourceRoot)
  val classFiles = compileAll(allSources)
  val jar = jarUp(resourceRoot, classFiles)

  def compileAll(sources: Task[Seq[PathRef]]) = {
    new Task.Subprocess(
      Seq(sources),
      args =>
        Seq("javac") ++
          args[Seq[PathRef]](0).map(_.path.toString) ++
          Seq("-d", args.dest.toString)
    ).map(_.dest)
  }

  def list(root: Task[PathRef]): Task[Seq[PathRef]] = {
    root.map(x => ls.rec(x.path).map(PathRef(_)))
  }

  case class jarUp(roots: Task[PathRef]*) extends Task[PathRef] {

    val inputs = roots

    def evaluate(args: Args): PathRef = {

      val output = new java.util.jar.JarOutputStream(new FileOutputStream(args.dest.toIO))
      for {
        root0 <- args.args
        root = root0.asInstanceOf[PathRef]

        path <- ls.rec(root.path)
        if path.isFile
      } {
        val relative = path.relativeTo(root.path)
        output.putNextEntry(new JarEntry(relative.toString))
        output.write(read.bytes(path))
      }
      output.close()
      args.dest
    }
  }

}

@main def main(): Any = Foo -> mill.Discovered[Foo.type]
