package mill.javascriptlib

import mill.*
import os.*
import ujson.*

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
      "@types/node@22.10.2",
      "@types/esbuild-copy-static-files@0.1.4",
      "typescript@5.7.2",
      "ts-node@^10.9.2",
      "esbuild@0.24.0",
      "esbuild-plugin-copy@2.1.1",
      "@esbuild-plugins/tsconfig-paths@0.1.2",
      "esbuild-copy-static-files@0.1.0",
      "tsconfig-paths@4.2.0",
      transitiveNpmDeps(),
      transitiveNpmDevDeps()
    ))
    PathRef(Task.dest)
  }

  def sources: T[PathRef] = Task.Source(millSourcePath / "src")

  def resources: T[Seq[PathRef]] = Task { Seq(PathRef(millSourcePath / "resources")) }

  def generatedSources: T[Seq[PathRef]] = Task { Seq[PathRef]() }

  def allSources: T[IndexedSeq[PathRef]] =
    Task {
      val fileExt: Path => Boolean = _.ext == "ts"
      val generated = for {
        pr <- generatedSources()
        file <- os.walk(pr.path)
        if fileExt(file)
      } yield PathRef(file)
      os.walk(sources().path).filter(fileExt).map(PathRef(_)) ++ generated
    }

  // specify tsconfig.compilerOptions
  def compilerOptions: T[Map[String, ujson.Value]] = Task {
    Map(
      "esModuleInterop" -> ujson.Bool(true),
      "declaration" -> ujson.Bool(true),
      "emitDeclarationOnly" -> ujson.Bool(true)
    )
  }

  // specify tsconfig.compilerOptions.Paths
  def compilerOptionsPaths: Task[Map[String, String]] =
    Task.Anon { Map("*" -> npmInstall().path.toString()) }

  private def upstreams: T[(PathRef, PathRef, Seq[PathRef])] = Task {
    val comp = compile()

    (comp._1, comp._2, resources())
  }

  def upstreamPathsBuilder: Task[Seq[(String, String)]] = Task.Anon {

    val upstreams = (for {
      ((comp, ts, res), mod) <- Task.traverse(moduleDeps)(_.upstreams)().zip(moduleDeps)
    } yield {
      Seq((
        mod.millSourcePath.subRelativeTo(Task.workspace).toString + "/*",
        (ts.path / "src").toString + ":" + (comp.path / "declarations").toString
      )) ++
        res.map { rp =>
          val resourceRoot = rp.path.last
          (
            "@" + mod.millSourcePath.subRelativeTo(Task.workspace).toString + s"/$resourceRoot/*",
            resourceRoot match {
              case s if s.contains(".dest") =>
                rp.path.toString
              case _ =>
                (ts.path / resourceRoot).toString
            }
          )
        }

    }).flatten

    upstreams
  }

  def modulePaths: Task[Seq[(String, String)]] = Task.Anon {
    val module = millSourcePath.last
    val typescriptOut = Task.dest / "typescript"
    val declarationsOut = Task.dest / "declarations"

    Seq((s"$module/*", (typescriptOut / "src").toString + ":" + declarationsOut.toString)) ++
      resources().map { rp =>
        val resourceRoot = rp.path.last
        val result = (
          s"@$module/$resourceRoot/*",
          resourceRoot match {
            case s if s.contains(".dest") => rp.path.toString
            case _ =>
              (typescriptOut / resourceRoot).toString
          }
        )
        result
      }
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

    os.copy(millSourcePath, Task.dest / "typescript", mergeFolders = true)
    os.call(npmInstall().path / "node_modules/typescript/bin/tsc")

    (PathRef(Task.dest), PathRef(Task.dest / "typescript"))
  }

  def mainFileName: T[String] = Task { s"${millSourcePath.last}.ts" }

  def mainFilePath: T[Path] = Task { compile()._2.path / "src" / mainFileName() }

  def forkEnv: T[Map[String, String]] =
    Task {
      Map("NODE_PATH" -> Seq(
        ".",
        compile()._1.path,
        compile()._2.path,
        npmInstall().path,
        npmInstall().path / "node_modules"
      ).mkString(":"))
    }

  def computedArgs: T[Seq[String]] = Task { Seq.empty[String] }

  def executionFlags: T[Map[String, String]] = Task { Map.empty[String, String] }

  def run(args: mill.define.Args): Command[CommandResult] = Task.Command {
    val mainFile = mainFilePath()
    val tsnode = npmInstall().path / "node_modules/.bin/ts-node"
    val tsconfigpaths = npmInstall().path / "node_modules/tsconfig-paths/register"
    val env = forkEnv()

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

  def bundleExternal: T[Seq[ujson.Value]] = Task { Seq(ujson.Str("fs"), ujson.Str("path")) }

  def bundleFlags: T[Map[String, ujson.Value]] = Task {
    Map(
      "entryPoints" -> ujson.Arr(mainFilePath().toString),
      "bundle" -> ujson.Bool(true),
      "platform" -> ujson.Str("node"),
    )
  }

  // configure esbuild with @esbuild-plugins/tsconfig-paths
  def bundleScriptBuilder: Task[String] = Task.Anon {
    val bundle = (Task.dest / "bundle.js").toString
    val rps = resources().map { p => p.path }.filter(os.exists)

    def envName(input: String): String = {
      val cleaned = input.replaceAll("[^a-zA-Z0-9]", "") // remove special characters
      cleaned.toUpperCase
    }

    val flags = bundleFlags().map { case (key, value) =>
      s"""  $key: $value,"""
    }.mkString("\n")

    val copyPluginCode =
      s"""
         |  plugins: [
         |    ${rps.map { rp =>
          s"""    copyStaticFiles({
             |      src: ${ujson.Str(rp.toString)},
             |      dest: ${ujson.Str(Task.dest.toString + "/" + rp.last.toString)},
             |      dereference: true,
             |      preserveTimestamps: true,
             |      recursive: true,
             |    }),
             """.stripMargin
        }.mkString(", \n")}
         |    TsconfigPathsPlugin({ tsconfig: 'tsconfig.json' }),
         |  ],
         |""".stripMargin

    val defineSection = resources().map { rp =>
      val resourceRoot = rp.path.last
      val envVarName = envName(resourceRoot)
      s""" "process.env.$envVarName": JSON.stringify(${ujson.Str("./" + resourceRoot)})"""
    }.mkString(",\n")

    s"""|import * as esbuild from 'node_modules/esbuild';
        |import TsconfigPathsPlugin from 'node_modules/@esbuild-plugins/tsconfig-paths'
        |import copyStaticFiles from 'node_modules/esbuild-copy-static-files';
        |import {join} from 'path';
        |
        |esbuild.build({
        |  $flags
        |  outfile: ${ujson.Str(bundle)},
        |  $copyPluginCode
        |  define: {
        |       $defineSection
        |    },
        |  external: [${bundleExternal().mkString(", ")}] // Exclude Node.js built-ins
        |}).then(() => {
        |  console.log('Build succeeded!');
        |}).catch((e) => {
        |  console.error('Build failed!');
        |  console.error(e)
        |  process.exit(1);
        |});
        |""".stripMargin

  }

  def bundle: T[PathRef] = Task {
    val env = forkEnv()
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
