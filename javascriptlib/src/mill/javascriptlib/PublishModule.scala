package mill.javascriptlib

import mill.*
import os.*

trait PublishModule extends TypeScriptModule {

  /**
   * Metadata about your project, required to publish.
   *
   * This is an equivalent of a `package.json`
   */
  override def npmDevDeps: T[Seq[String]] = Task {
    Seq(
      "glob@^10.4.5",
      "ts-patch@3.3.0",
      "typescript-transform-paths@3.5.3"
    )
  }

  def pubBundledOut: T[String] = Task { "dist" }

  // main file; defined with mainFileName
  def pubMain: T[String] =
    Task { pubBundledOut() + "/src/" + mainFileName().replaceAll("\\.ts", ".js") }

  private def pubMainType: T[String] = Task {
    pubMain().replaceFirst(pubBundledOut(), declarationDir()).replaceAll("\\.js", ".d.ts")
  }

  private def pubTypesVersion: T[Map[String, Seq[String]]] = Task {
    tscAllSources().map { source =>
      val dist = pubBundledOut() + "/" + source // source.replaceFirst("t-", )
      val declarations = declarationDir() + "/" + source // source.replaceFirst("t-", )
      ("./" + dist).replaceAll("\\.ts", "") -> Seq(declarations.replaceAll("\\.ts", ".d.ts"))
    }.toMap
  }

  def pubPackageJson: T[PathRef] = Task {
    val json = packageJson()
    val updatedJson =
      json
        .copy(
          files =
            if (json.files.obj.isEmpty) Seq(pubBundledOut(), declarationDir())
            else json.files,
          main = if (json.main.isEmpty) pubMain() else json.main,
          types = if (json.types.isEmpty) pubMainType() else json.types,
          exports =
            if (json.exports.obj.isEmpty) ujson.Obj("." -> ("./" + pubMain())) else json.exports,
          typesVersions =
            if (json.typesVersions.value.isEmpty) pubTypesVersion() else json.typesVersions
        )
        .cleanJson

    os.write.over(T.dest / "package.json", updatedJson.render(2))

    PathRef(T.dest)
  }

  // Compilation Options
  override def compilerOptions: T[Map[String, ujson.Value]] = Task {
    Map(
      "declarationMap" -> ujson.Bool(true),
      "esModuleInterop" -> ujson.Bool(true),
      "baseUrl" -> ujson.Str("."),
      "rootDir" -> ujson.Str("."),
      "declaration" -> ujson.Bool(true),
      "outDir" -> ujson.Str(pubBundledOut()),
      "plugins" -> ujson.Arr(
        ujson.Obj("transform" -> "typescript-transform-paths"),
        ujson.Obj(
          "transform" -> "typescript-transform-paths",
          "afterDeclarations" -> true
        )
      ),
      "moduleResolution" -> ujson.Str("node"),
      "module" -> ujson.Str("CommonJS"),
      "target" -> ujson.Str("ES2020")
    )
  }

  /**
   * Patch tsc with custom transformer: `typescript-transform-paths`.
   * `ts-paths` can now be transformed to their relative path in the
   *  bundled code
   */
  private def pubTsPatchInstall: T[Unit] = Task {
    os.call(
      ("node", npmInstall().path / "node_modules/ts-patch/bin/ts-patch", "install"),
      cwd = npmInstall().path
    )
    ()
  }

  override def compile: T[PathRef] = Task {
    tscCopySources()
    tscCopyModDeps()
    tscCopyGenSources()
    tscLinkResources()
    pubTsPatchInstall()
    createNodeModulesSymlink()
    mkTsconfig()

    // build declaration and out dir
    os.call("node_modules/typescript/bin/tsc", cwd = T.dest)

    PathRef(T.dest)
  }
  // Compilation Options

  // EsBuild - Copying Resources
  // we use tsc to compile to js & ts-patch to transform ts-paths
  // esbuild script serves to copy resources to dist/
  override def bundleScriptBuilder: Task[String] = Task.Anon {
    def envName(input: String): String = {
      val cleaned = input.replaceAll("[^a-zA-Z0-9]", "") // remove special characters
      cleaned.toUpperCase
    }

    val copyPluginCode =
      s"""
         |  plugins: [
         |    ${resources().map { p => p.path }.filter(os.exists).map { rp =>
          s"""    copyStaticFiles({
             |      src: ${ujson.Str(rp.toString)},
             |      dest: ${ujson.Str(
              compile().path.toString + "/" + pubBundledOut() + "/" + rp.last
            )},
             |      dereference: true,
             |      preserveTimestamps: true,
             |      recursive: true,
             |    }),
             """.stripMargin
        }.mkString("\n")}
         |    TsconfigPathsPlugin({ tsconfig: 'tsconfig.json' }),
         |  ],
         |""".stripMargin

    val defineSection = resources().map { rp =>
      val resourceRoot = rp.path.last
      val envVarName = envName(resourceRoot)
      s""" "process.env.$envVarName": JSON.stringify(${ujson.Str("./" + resourceRoot)})"""
    }.distinct.mkString(",\n")

    s"""|import * as esbuild from 'esbuild';
        |import * as glob from 'glob';
        |import TsconfigPathsPlugin from '@esbuild-plugins/tsconfig-paths'
        |import copyStaticFiles from 'esbuild-copy-static-files';
        |
        |esbuild.build({
        |  entryPoints: [],
        |  outdir: ${ujson.Str("./" + pubBundledOut())},
        |  write: ${ujson.Bool(false)}, // Prevent esbuild from generating new files
        |  $copyPluginCode
        |  define: {
        |       $defineSection
        |    },
        |}).then(() => {
        |  console.log('Build succeeded!');
        |}).catch((e) => {
        |  console.error('Build failed!');
        |  console.error(e)
        |  process.exit(1);
        |});
        |""".stripMargin

  }

  // EsBuild - END

  def publish(): Command[Unit] = Task.Command {
    // bundled code for publishing
    val bundled = bundle().path / os.up

    os.walk(bundled)
      .foreach(p => os.copy.over(p, T.dest / p.relativeTo(bundled), createFolders = true))

    // build package.json
    os.copy.over(pubPackageJson().path / "package.json", T.dest / "package.json")

    // run npm publish
    os.call(("npm", "publish"), stdout = os.Inherit, cwd = T.dest)
    ()
  }

}
