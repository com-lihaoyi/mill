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
      "@esbuild-plugins/tsconfig-paths@0.1.2",
      transitiveNpmDeps(),
      transitiveNpmDevDeps()
    ))
    PathRef(Task.dest)
  }

  def sources: Target[PathRef] = Task.Source(millSourcePath / "src")

  def allSources: Target[IndexedSeq[PathRef]] =
    Task { os.walk(sources().path).filter(_.ext == "ts").map(PathRef(_)) }

  // specify tsconfig.compilerOptions
  def compilerOptions: Task[Map[String, ujson.Value]] = Task.Anon {
    Map(
      "esModuleInterop" -> ujson.Bool(true),
      "declaration" -> ujson.Bool(true),
      "emitDeclarationOnly" -> ujson.Bool(true),
      "typeRoots" -> ujson.Arr((npmInstall().path / "node_modules/@types").toString)
    )
  }

  // specify tsconfig.compilerOptions.Paths
  def compilerOptionsPaths: Task[Map[String, String]] =
    Task.Anon { Map("*" -> sources().path.toString(), "*" -> npmInstall().path.toString()) }

  def compilerOptionsBuilder: Task[Map[String, ujson.Value]] = Task.Anon {
    val declarationsOut = Task.dest / "declarations"

    val upstreamPaths =
      for {
        ((comp, ts), mod) <- Task.traverse(moduleDeps)(_.compile)().zip(moduleDeps)
      } yield {
        (
          mod.millSourcePath.subRelativeTo(Task.workspace).toString + "/*",
          (ts.path / "src").toString + ":" + (comp.path / "declarations").toString
        )
      }

    val combinedPaths = upstreamPaths ++ compilerOptionsPaths().toSeq
    val combinedCompilerOptions: Map[String, ujson.Value] = Map(
      "declarationDir" -> ujson.Str(declarationsOut.toString),
      "paths" -> ujson.Obj.from(combinedPaths.map { case (k, v) =>
        val splitValues =
          v.split(":").map(s => s"$s/*") // Split by ":" and append "/*" to each part
        (k, ujson.Arr.from(splitValues))
      })
    ) ++ compilerOptions()

    combinedCompilerOptions
  }

  def compile: T[(PathRef, PathRef)] = Task {
    os.write(
      Task.dest / "tsconfig.json",
      ujson.Obj(
        "compilerOptions" -> ujson.Obj.from(compilerOptionsBuilder().toSeq),
        "files" -> allSources().map(_.path.toString)
      )
    )

    os.call(npmInstall().path / "node_modules/typescript/bin/tsc")
    os.copy.over(millSourcePath, Task.dest / "typescript")

    (PathRef(Task.dest), PathRef(Task.dest / "typescript"))
  }

  def mainFileName: Target[String] = Task { s"${millSourcePath.last}.ts" }

  def mainFilePath: Target[Path] = Task { compile()._2.path / "src" / mainFileName() }

  def mkENV: Task[Map[String, String]] =
    Task.Anon { Map("NODE_PATH" -> Seq(".", compile()._2.path, npmInstall().path).mkString(":")) }

  // define computed arguments and where they should be placed (before/after user arguments)
  def computedArgs: Task[(Option[Seq[String]], Option[Seq[String]])] = Task { (None, None) }

  def run(args: mill.define.Args): Command[CommandResult] = Task.Command {
    val (mainFile, env) = (mainFilePath(), mkENV())
    val tsnode = npmInstall().path / "node_modules/.bin/ts-node"

    val orderedArgs: Seq[String] = computedArgs() match {
      case (None, None) => args.value
      case (Some(x), None) => x ++ args.value
      case (None, Some(x)) => x ++ args.value
      case (Some(x), Some(y)) => x ++ args.value ++ y
    }

    os.call(
      (tsnode, mainFile, orderedArgs),
      stdout = os.Inherit,
      env = env
    )
  }

  def bundleFlags: Task[Map[String, Seq[String]]] = Task.Anon { Map.empty[String, Seq[String]] }

  // configure esbuild with @esbuild-plugins/tsconfig-paths
  def bundleScriptBuilder: Task[String] = Task.Anon {
    val mainFile = mainFilePath()
    val bundle = Task.dest / "bundle.js"

    val flags = bundleFlags().map { case (key, values) =>
      s"""  $key: [${values.map(v => s"'$v'").mkString(", ")}],"""
    }.mkString("\n")

    s"""|import * as esbuild from 'node_modules/esbuild';
        |import TsconfigPathsPlugin from 'node_modules/@esbuild-plugins/tsconfig-paths'
        |
        |esbuild.build({
        |  entryPoints: ['$mainFile'],
        |  bundle: true,
        |  outfile: '$bundle',
        |  plugins: [TsconfigPathsPlugin({tsconfig: 'tsconfig.json'})],
        |  platform: 'node'
        |  $flags
        |}).then(() => {
        |  console.log('Build succeeded!');
        |}).catch(() => {
        |  console.error('Build failed!');
        |  process.exit(1);
        |});
        |""".stripMargin

  }

  def bundle: Target[PathRef] = Task {
    val env = mkENV()
    val tsnode = npmInstall().path / "node_modules/.bin/ts-node"
    val bundleScript = compile()._1.path / "build.ts"
    val bundle = Task.dest / "bundle.js"

    os.write.over(
      bundleScript,
      bundleScriptBuilder()
    )

    os.call(
      (tsnode, bundleScript),
      stdout = os.Inherit,
      env = env,
      cwd = compile()._1.path
    )
    PathRef(bundle)
  }

}
