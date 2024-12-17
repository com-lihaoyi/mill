package mill.javascriptlib

import mill.*
import os.*
import scala.util.Try

trait TypeScriptModule extends Module { outer =>
  def moduleDeps: Seq[TypeScriptModule] = Nil

  def npmDeps: T[Seq[String]] = Task { Seq.empty[String] }

  def npmDevDeps: T[Seq[String]] = Task { Seq.empty[String] }

  def transitiveNpmDeps: T[Seq[String]] = Task {
    Task.traverse(moduleDeps)(_.npmDeps)().flatten ++ npmDeps()
  }

  def transitiveNpmDevDeps: T[Seq[String]] = Task {
    Task.traverse(moduleDeps)(_.npmDevDeps)().flatten ++ npmDevDeps()
  }

  def npmInstall: T[PathRef] = Task {
    os.call((
      "npm",
      "install",
      "--save-dev",
      "@types/node@22.7.8",
      "typescript@5.6.3",
      "ts-node@^10.9.2",
      "esbuild@0.24.0",
      "esbuild-plugin-copy@2.1.1",
      "@esbuild-plugins/tsconfig-paths@0.1.2",
      "tsconfig-paths@4.2.0",
      transitiveNpmDeps(),
      transitiveNpmDevDeps()
    ))
    PathRef(Task.dest)
  }

  def sources: T[PathRef] = Task.Source(millSourcePath / "src")

  def resources: T[PathRef] = Task.Source(millSourcePath / "resources")

  def generatedSources: T[Seq[PathRef]] = Task { Seq[PathRef]() }

  def allSources: T[IndexedSeq[PathRef]] =
    Task {
      val fileExt: Path => Boolean = _.ext == "ts"

      val generated =
        generatedSources().flatMap(pr =>
          os.walk(pr.path).filter(fileExt).map(PathRef(_))
        ).toIndexedSeq

      val resources_ =
        Try(os.walk(resources().path)).getOrElse(IndexedSeq()).filter(fileExt).map(PathRef(_))

      os.walk(sources().path).filter(fileExt).map(PathRef(_)) ++
        resources_ ++
        generated
    }

  // specify tsconfig.compilerOptions
  def compilerOptions: Task[Map[String, ujson.Value]] = Task {
    Map(
      "esModuleInterop" -> ujson.Bool(true),
      "declaration" -> ujson.Bool(true),
      "emitDeclarationOnly" -> ujson.Bool(true)
    )
  }

  // specify tsconfig.compilerOptions.Paths
  def compilerOptionsPaths: Task[Map[String, String]] =
    Task { Map("*" -> npmInstall().path.toString()) }

  def upstreamPathsBuilder: Task[Seq[(String, String)]] = Task.Anon {
    val upstreams = (for {
      ((comp, ts), mod) <- Task.traverse(moduleDeps)(_.compile)().zip(moduleDeps)
    } yield {
      Seq(
        (
          mod.millSourcePath.subRelativeTo(Task.workspace).toString + "/*",
          (ts.path / "src").toString + ":" + (comp.path / "declarations").toString
        ),
        (
          mod.millSourcePath.subRelativeTo(Task.workspace).toString + "/resources/*",
          (ts.path / "resources").toString + ":" + (comp.path / "declarations").toString
        )
      )
    }).flatten

    upstreams
  }

  def modulePaths: Task[Seq[(String, String)]] = Task.Anon {
    val module = millSourcePath.last
    val declarationsOut = Task.dest / "declarations"

    Seq(
      (
        s"$module/resources/*",
        (Task.dest / "typescript/resources").toString + ":" + declarationsOut.toString
      ),
      (s"$module/*", sources().path.toString + ":" + declarationsOut.toString)
    )
  }

  def compilerOptionsBuilder: Task[Map[String, ujson.Value]] = Task.Anon {
    val declarationsOut = Task.dest / "declarations"

    val combinedPaths =
      upstreamPathsBuilder() ++
        generatedSources().map(p => ("@generated/*", p.path.toString)) ++
        modulePaths() ++
        compilerOptionsPaths().toSeq

    val combinedCompilerOptions: Map[String, ujson.Value] = compilerOptions() ++ Map(
      "declarationDir" -> ujson.Str(declarationsOut.toString),
      "typeRoots" -> ujson.Arr(
        (npmInstall().path / "node_modules/@types").toString,
        declarationsOut.toString
      ),
      "paths" -> ujson.Obj.from(combinedPaths.map { case (k, v) =>
        val splitValues =
          v.split(":").map(s => s"$s/*") // Split by ":" and append "/*" to each part
        (k, ujson.Arr.from(splitValues))
      })
    )

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

    os.copy.over(millSourcePath, Task.dest / "typescript")
    os.call(npmInstall().path / "node_modules/typescript/bin/tsc")

    (PathRef(Task.dest), PathRef(Task.dest / "typescript"))
  }

  def mainFileName: T[String] = Task { s"${millSourcePath.last}.ts" }

  def mainFilePath: T[Path] = Task { compile()._2.path / "src" / mainFileName() }

  def mkENV: T[Map[String, String]] =
    Task {
      Map("NODE_PATH" -> Seq(
        ".",
        compile()._1.path,
        compile()._2.path,
        npmInstall().path,
        npmInstall().path / "node_modules"
      ).mkString(":"))
    }

  def computedArgs: Task[Seq[String]] = Task { Seq.empty[String] }

  def executionFlags: Task[Map[String, String]] = Task { Map.empty[String, String] }

  def run(args: mill.define.Args): Command[CommandResult] = Task.Command {
    val mainFile = mainFilePath()
    val tsnode = npmInstall().path / "node_modules/.bin/ts-node"
    val tsconfigpaths = npmInstall().path / "node_modules/tsconfig-paths/register"
    val env = mkENV()

    val execFlags: Seq[String] = executionFlags().map {
      case (key, "") => s"--$key"
      case (key, value) => s"--$key=$value"
      case _ => ""
    }.toSeq

    os.call(
      (
        "node",
        execFlags,
        tsnode,
        "-r",
        tsconfigpaths,
        mainFile,
        computedArgs(),
        args.value
      ),
      stdout = os.Inherit,
      env = env,
      cwd = compile()._1.path
    )
  }

  def bundleFlags: T[Map[String, String]] = Task {
    Map(
      "platform" -> "'node'",
      "entryPoints" -> s"['${mainFilePath()}']",
      "bundle" -> "true"
    )
  }

  // configure esbuild with @esbuild-plugins/tsconfig-paths
  def bundleScriptBuilder: Task[String] = Task.Anon {
    val bundle = Task.dest / "bundle.js"
    val resourcePath = compile()._2.path / "resources"

    val flags = bundleFlags().map { case (key, value) =>
      s"""  $key: $value,"""
    }.mkString("\n")

    s"""|import * as esbuild from 'node_modules/esbuild';
        |import TsconfigPathsPlugin from 'node_modules/@esbuild-plugins/tsconfig-paths'
        |import Copy from 'node_modules/esbuild-plugin-copy';
        |
        |esbuild.build({
        |  $flags
        |  outfile: '$bundle',
        |  plugins: [
        |  Copy({
        |    resolveFrom: 'cwd',
        |    assets: {
        |       from: ['$resourcePath' + '/**/*'],
        |       to: ['${Task.dest}' + '/resources']
        |    }
        |  }),
        |  TsconfigPathsPlugin({tsconfig: 'tsconfig.json'}),
        |  ]
        |}).then(() => {
        |  console.log('Build succeeded!');
        |}).catch(() => {
        |  console.error('Build failed!');
        |  process.exit(1);
        |});
        |""".stripMargin

  }

  def bundle: T[PathRef] = Task {
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

  trait TypeScriptTests extends TypeScriptModule {
    override def moduleDeps: Seq[TypeScriptModule] = Seq(outer) ++ outer.moduleDeps
  }

}
