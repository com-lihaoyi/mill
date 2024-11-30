package mill.javascriptlib
import mill.*
import os.*

import scala.collection.immutable.IndexedSeq

trait TypeScriptModule extends Module {
  def moduleDeps: Seq[TypeScriptModule] = Nil

  def npmDeps: T[Seq[String]] = Task { Seq.empty[String] }

  def npmDevDeps: T[Seq[String]] = Task { Seq.empty[String] }

  def transitiveNpmDeps: T[Seq[String]] = Task {
    Task.traverse(moduleDeps)(_.npmDeps)().flatten ++ npmDeps()
  }

  def transitiveNpmDevDeps: T[Seq[String]] = Task {
    Task.traverse(moduleDeps)(_.npmDevDeps)().flatten ++ npmDevDeps()
  }

  def npmInstall: Target[PathRef] = Task {
    os.call((
      "npm",
      "install",
      "--save-dev",
      "@types/node@22.7.8",
      "typescript@5.6.3",
      "ts-node@^10.9.2",
      "esbuild@0.24.0",
      transitiveNpmDeps(),
      transitiveNpmDevDeps()
    ))
    PathRef(Task.dest)
  }

  def sources: Target[PathRef] = Task.Source(millSourcePath / "src")

  def allSources: Target[IndexedSeq[PathRef]] =
    Task { os.walk(sources().path).filter(_.ext == "ts").map(PathRef(_)) }

  // specify tsconfig.compilerOptions
  def tsCompilerOptions: Target[Map[String, ujson.Value]] = Task {
    Map(
      "esModuleInterop" -> ujson.Bool(true),
      "declaration" -> ujson.Bool(true),
      "typeRoots" -> ujson.Arr((npmInstall().path / "node_modules/@types").toString())
    )
  }

  // specify tsconfig.compilerOptions.Paths
  def tsCompilerOptionsPaths: Target[Map[String, String]] =
    Task { Map("*" -> sources().path.toString(), "*" -> npmInstall().path.toString()) }

  def compile: T[(PathRef, PathRef)] = Task {
    val javascriptOut = Task.dest / "javascript"
    val declarationsOut = Task.dest / "declarations"

    val upstreamPaths =
      for (((_, dTsDir), mod) <- Task.traverse(moduleDeps)(_.compile)().zip(moduleDeps))
        yield (mod.millSourcePath.subRelativeTo(Task.workspace).toString + "/*", dTsDir.path)

    val combinedPaths = upstreamPaths ++ tsCompilerOptionsPaths().toSeq
    val combinedCompilerOptions: Map[String, ujson.Value] = Map(
      "declarationDir" -> ujson.Str(declarationsOut.toString),
      "outDir" -> ujson.Str(javascriptOut.toString),
      "paths" -> ujson.Obj.from(combinedPaths.map { case (k, v) => (k, ujson.Arr(s"$v/*")) })
    ) ++ tsCompilerOptions()

    os.write(
      Task.dest / "tsconfig.json",
      ujson.Obj(
        "compilerOptions" -> ujson.Obj.from(combinedCompilerOptions.toSeq),
        "files" -> allSources().map(_.path.toString)
      )
    )

    os.call(npmInstall().path / "node_modules/typescript/bin/tsc")

    (PathRef(javascriptOut), PathRef(declarationsOut))
  }

  def mainFileName: Target[String] = Task { s"${millSourcePath.last}.js" }

  def mainFilePath: Target[Path] = Task { compile()._1.path / mainFileName() }

  def mkENV =
    Task.Anon { Map("NODE_PATH" -> Seq(".", compile()._1.path, npmInstall().path).mkString(":")) }

  def prepareRun: Task[(Path, Map[String, String])] = Task.Anon {
    val upstream = Task.traverse(moduleDeps)(_.compile)().zip(moduleDeps)
    for (((jsDir, _), mod) <- upstream) {
      os.copy(jsDir.path, Task.dest / mod.millSourcePath.subRelativeTo(Task.workspace))
    }
    (mainFilePath(), mkENV())
  }

  // define computed arguments and where they should be placed (before/after user arguments)
  def computedArgs: Task[(Option[Seq[String]], Option[Seq[String]])] = Task { (None, None) }

  def run(args: mill.define.Args): Command[CommandResult] = Task.Command {
    val (mainFile, env) = prepareRun()
    val orderedArgs: Seq[String] = computedArgs() match {
      case (None, None) => args.value
      case (Some(x), None) => x ++ args.value
      case (None, Some(x)) => x ++ args.value
      case (Some(x), Some(y)) => x ++ args.value ++ y
    }

    // before ++ args.value ++ after

    os.call(("node", mainFile, orderedArgs), stdout = os.Inherit, env = env)
  }

  def bundleFlags: Target[Seq[String]] = Task { Seq("--platform=node") }

  def bundle: Target[PathRef] = Task {
    val (mainFile, env) = prepareRun()
    val esbuild = npmInstall().path / "node_modules/esbuild/bin/esbuild"
    val bundle = Task.dest / "bundle.js"
    os.call((esbuild, mainFile, bundleFlags(), "--bundle", s"--outfile=$bundle"), env = env)
    PathRef(bundle)
  }

}
