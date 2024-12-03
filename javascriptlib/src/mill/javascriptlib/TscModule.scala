package mill.javascriptlib
import mill._

trait TscModule extends NodeModule {
  def npmDevDeps = Task {
    Seq(
      "typescript@5.6.3",
      "@types/node@22.7.8",
      "esbuild@0.24.0"
    )
  }

  def sources = Task.Source(millSourcePath / "src")
  def allSources = Task {
    os.walk(sources().path).filter(file => file.ext == "ts" || file.ext == "tsx").map(PathRef(_))
  }

  def tscArgs = Task { Seq.empty[String] }

  def tscModuleDeps: Seq[TscModule] =
    moduleDeps.filter(_.isInstanceOf[TscModule]).map(_.asInstanceOf[TscModule])

  def compile: T[(PathRef, PathRef)] = Task {
    val nodeTypes = install().path / "node_modules/@types"
    val javascriptOut = Task.dest / "javascript"
    val declarationsOut = Task.dest / "declarations"

    val upstreamPaths =
      for (((jsDir, dTsDir), mod) <- Task.traverse(tscModuleDeps)(_.compile)().zip(moduleDeps))
        yield (mod.millSourcePath.subRelativeTo(Task.workspace).toString + "/*", dTsDir.path)

    val allPaths = upstreamPaths ++ Seq("*" -> sources().path, "*" -> install().path)

    os.write(
      Task.dest / "tsconfig.json",
      ujson.Obj(
        "compilerOptions" -> ujson.Obj(
          "outDir" -> javascriptOut.toString,
          "declaration" -> true,
          "declarationDir" -> declarationsOut.toString,
          "typeRoots" -> ujson.Arr(nodeTypes.toString),
          "paths" -> ujson.Obj.from(allPaths.map { case (k, v) => (k, ujson.Arr(s"$v/*")) })
        ),
        "files" -> allSources().map(_.path.toString)
      )
    )

    os.call((install().path / "node_modules/typescript/bin/tsc", tscArgs()))

    (PathRef(javascriptOut), PathRef(declarationsOut))
  }

  def mainFileName = Task { s"${millSourcePath.last}.js" }

  def prepareRun = Task.Anon {
    val upstream = Task.traverse(tscModuleDeps)(_.compile)().zip(moduleDeps)
    for (((jsDir, tTsDir), mod) <- upstream) {
      os.copy(jsDir.path, Task.dest / mod.millSourcePath.subRelativeTo(Task.workspace))
    }
    val mainFile = compile()._1.path / mainFileName()
    val env = Map("NODE_PATH" -> Seq(".", compile()._1.path, install().path).mkString(":"))
    (mainFile, env)
  }

  def run(args: mill.define.Args) = Task.Command {
    val (mainFile, env) = prepareRun()
    os.call(("node", mainFile, args.value), stdout = os.Inherit, env = env)
  }

  def bundle = Task {
    val (mainFile, env) = prepareRun()
    val esbuild = install().path / "node_modules/esbuild/bin/esbuild"
    val bundle = Task.dest / "bundle.js"
    os.call((esbuild, mainFile, "--bundle", s"--outfile=$bundle"), env = env)
    PathRef(bundle)
  }
}
