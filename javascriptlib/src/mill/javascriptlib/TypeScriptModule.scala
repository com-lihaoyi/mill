package mill.javascriptlib
import mill.*
import os.*

trait TypeScriptModule extends Module {
  def moduleDeps: Seq[TypeScriptModule] = Nil

  def npmDeps: T[Seq[String]] = Task { Seq.empty[String] }

  def transitiveNpmDeps: T[Seq[String]] = Task {
    Task.traverse(moduleDeps)(_.npmDeps)().flatten ++ npmDeps()
  }

  def npmInstall: Target[PathRef] = Task {
    os.call((
      "npm",
      "install",
      "--save-dev",
      "typescript@5.6.3",
      "@types/node@22.7.8",
      "esbuild@0.24.0",
      transitiveNpmDeps()
    ))
    PathRef(Task.dest)
  }

  def sources: Target[PathRef] = Task.Source(millSourcePath / "src")

  def allSources: Target[IndexedSeq[PathRef]] =
    Task { os.walk(sources().path).filter(_.ext == "ts").map(PathRef(_)) }

  def compile: T[(PathRef, PathRef)] = Task {
    val nodeTypes = npmInstall().path / "node_modules/@types"
    val javascriptOut = Task.dest / "javascript"
    val declarationsOut = Task.dest / "declarations"

    val upstreamPaths =
      for (((jsDir, dTsDir), mod) <- Task.traverse(moduleDeps)(_.compile)().zip(moduleDeps))
        yield (mod.millSourcePath.subRelativeTo(Task.workspace).toString + "/*", dTsDir.path)

    val allPaths = upstreamPaths ++ Seq("*" -> sources().path, "*" -> npmInstall().path)

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

    os.call((npmInstall().path / "node_modules/typescript/bin/tsc"))

    (PathRef(javascriptOut), PathRef(declarationsOut))
  }

  def mainFileName: Target[String] = Task { s"${millSourcePath.last}.js" }

  def mainFilePath: Target[Path] = Task { compile()._1.path / mainFileName() }

  def mkENV: Target[Map[String, String]] =
    Task { Map("NODE_PATH" -> Seq(".", compile()._1.path, npmInstall().path).mkString(":")) }

  def prepareRun: Task[(Path, Map[String, String])] = Task.Anon {
    val upstream = Task.traverse(moduleDeps)(_.compile)().zip(moduleDeps)
    for (((jsDir, _), mod) <- upstream) {
      os.copy(jsDir.path, Task.dest / mod.millSourcePath.subRelativeTo(Task.workspace))
    }
    (mainFilePath(), mkENV())
  }

  def runArguments: Task[String] = Task { "" }

  def run(args: mill.define.Args): Command[CommandResult] = Task.Command {
    val (mainFile, env) = prepareRun()
    os.call(("node", mainFile, args.value, runArguments()), stdout = os.Inherit, env = env)
  }

  def bundleFlags: Target[String] = Task { "--platform=node" }

  def bundle: Target[PathRef] = Task {
    val (mainFile, env) = prepareRun()
    val esbuild = npmInstall().path / "node_modules/esbuild/bin/esbuild"
    val bundle = Task.dest / "bundle.js"
    os.call((esbuild, mainFile, "--bundle", bundleFlags(), s"--outfile=$bundle"), env = env)
    PathRef(bundle)
  }
}
