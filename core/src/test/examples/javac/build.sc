object Foo {

  import java.io.FileOutputStream
  import java.util.jar.JarEntry

  import ammonite.ops.{ls, pwd, read}
  import forge.{Discovered, Target}
  import forge.util.{Args, PathRef}

  val workspacePath = pwd / 'target / 'workspace / 'javac
  val javacSrcPath = pwd / 'src / 'test / 'examples / 'javac
  val javacDestPath = workspacePath / 'src

  val sourceRootPath = javacDestPath / 'src
  val resourceRootPath = javacDestPath / 'resources

  // sourceRoot -> allSources -> classFiles
  //                                |
  //                                v
  //           resourceRoot ---->  jar
  val sourceRoot = Target.path(sourceRootPath)
  val resourceRoot = Target.path(resourceRootPath)
  val allSources = list(sourceRoot)
  val classFiles = compileAll(allSources)
  val jar = jarUp(resourceRoot, classFiles)

  def compileAll(sources: Target[Seq[PathRef]]) = {
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

  case class jarUp(roots: Target[PathRef]*) extends Target[PathRef] {

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
      PathRef(args.dest)
    }
  }

}

@main def main(): Any = Foo -> forge.Discovered[Foo.type]
