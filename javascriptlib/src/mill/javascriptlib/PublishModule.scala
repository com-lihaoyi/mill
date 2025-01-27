package mill.javascriptlib

import mill.*
import os.*
import mill.scalalib.publish.licenseFormat

trait PublishModule extends TypeScriptModule {

  /**
   * Metadata about your project, required to publish.
   *
   * This is an equivalent of a `package.json`
   */
  def publishMeta: T[PublishModule.PublishMeta]

  override def npmDevDeps: T[Seq[String]] =
    Task { Seq("glob@^10.4.5", "ts-patch@3.3.0", "typescript-transform-paths@3.5.3") }

  def pubBundledOut: T[String] = Task { "dist" }

  private def pubDeclarationOut: T[String] = Task { "declarations" }

  override def mainFileName: T[String] = Task { s"${millSourcePath.last}.js" }

  // main file; defined with mainFileName
  def pubMain: T[String] =
    Task { pubBundledOut() + "/src/" + mainFileName() }

  private def pubMainType: T[String] = Task {
    pubMain().replaceFirst(pubBundledOut(), pubDeclarationOut()).replaceAll("\\.js", ".d.ts")
  }

  // Define exports for the package
  // by default: mainFile is exported
  // use this to define other exports
  def pubExports: T[Map[String, String]] = Task { Map.empty[String, String] }

  private def pubBuildExports: T[Map[String, PublishModule.ExportEntry]] = Task {
    pubExports().map { case (key, value) =>
      key -> PublishModule.Export("./" + pubBundledOut() + "/" + value)
    }
  }

  private def pubTypesVersion: T[Map[String, Seq[String]]] = Task {
    pubAllSources().map { source =>
      val dist = source.replaceFirst("typescript", pubBundledOut())
      val declarations = source.replaceFirst("typescript", pubDeclarationOut())
      ("./" + dist).replaceAll("\\.ts", "") -> Seq(declarations.replaceAll("\\.ts", ".d.ts"))
    }.toMap
  }

  // build package.json from publishMeta
  // mv to publishDir.dest
  def pubbPackageJson: T[PathRef] = Task { // PathRef
    def splitDeps(input: String): (String, String) = {
      input.split("@", 3).toList match {
        case first :: second :: tail if input.startsWith("@") =>
          ("@" + first + "@" + second, tail.mkString)
        case first :: tail =>
          (first, tail.mkString)
      }
    }

    val json = publishMeta()
    val updatedJson = json.copy(
      files = json.files ++ Seq(pubBundledOut(), pubDeclarationOut()),
      main = pubMain(),
      types = pubMainType(),
      exports = Map("." -> PublishModule.Export("./" + pubMain())) ++ pubBuildExports(),
      bin = json.bin.map { case (k, v) => (k, "./" + pubBundledOut() + "/" + v) },
      typesVersions = pubTypesVersion(),
      dependencies = transitiveNpmDeps().map { deps => splitDeps(deps) }.toMap,
      devDependencies = transitiveNpmDeps().map { deps => splitDeps(deps) }.toMap
    ).toJsonClean

    os.write.over(Task.dest / "package.json", updatedJson)

    PathRef(Task.dest)
  }

  // Package.Json construction

  // Compilation Options
  override def modulePaths: Task[Seq[(String, String)]] = Task.Anon {
    val module = millSourcePath.last

    Seq((s"$module/*", "typescript/src" + ":" + s"${pubDeclarationOut()}")) ++
      resources().map { rp =>
        val resourceRoot = rp.path.last
        val result = (
          s"@$module/$resourceRoot/*",
          resourceRoot match {
            case s if s.contains(".dest") => rp.path.toString
            case _ => s"typescript/$resourceRoot"
          }
        )
        result
      }
  }

  private def pubModDeps: T[Seq[String]] = Task {
    moduleDeps.map { _.millSourcePath.subRelativeTo(Task.workspace).segments.head }.distinct
  }

  private def pubModDepsSources: T[Seq[PathRef]] = Task {
    for {
      modSource <- Task.traverse(moduleDeps)(_.sources)
    } yield modSource
  }

  private def pubBaseModeGenSources: T[Seq[PathRef]] = Task {
    for {
      pr <- generatedSources()
      file <- os.walk(pr.path)
      if file.ext == "ts"
    } yield PathRef(file)
  }

  private def pubModDepsGenSources: T[Seq[PathRef]] = Task {
    Task.traverse(moduleDeps)(_.generatedSources)().flatMap { modSource =>
      val fileExt: Path => Boolean = _.ext == "ts"
      for {
        pr <- modSource
        file <- os.walk(pr.path)
        if fileExt(file)
      } yield PathRef(file)
    }
  }

  // mv generated sources for base mod and its deps
  private def pubGenSources: T[Unit] = Task {
    val allGeneratedSources = pubBaseModeGenSources() ++ pubModDepsGenSources()
    allGeneratedSources.foreach { target =>
      val destination = publishDir().path / "typescript" / "generatedSources" / target.path.last
      os.makeDir.all(destination / os.up)
      os.copy.over(
        target.path,
        destination
      )
    }
  }

  private def pubCopyModDeps: T[Unit] = Task {
    val targets = pubModDeps()

    targets.foreach { target =>
      val destination = publishDir().path / "typescript" / target
      os.makeDir.all(destination / os.up)
      os.copy(
        Task.workspace / target,
        destination,
        mergeFolders = true
      )
    }
  }

  override def resources: T[Seq[PathRef]] = Task {
    val modDepsResources = moduleDeps.map { x => PathRef(x.millSourcePath / "resources") }
    Seq(PathRef(millSourcePath / "resources")) ++ modDepsResources
  }

  /**
   * Generate sources relative to publishDir / "typescript"
   */
  private def pubAllSources: T[IndexedSeq[String]] = Task {
    val project = Task.workspace.toString
    val fileExt: Path => Boolean = _.ext == "ts"
    (for {
      source <-
        os.walk(sources().path) ++ pubModDepsSources().toIndexedSeq.flatMap(pr =>
          os.walk(pr.path)
        ).filter(fileExt)
    } yield source.toString
      .replaceFirst(millSourcePath.toString, "typescript")
      .replaceFirst(
        project,
        "typescript"
      )) ++ (pubBaseModeGenSources() ++ pubModDepsGenSources()).map(pr =>
      "typescript/generatedSources/" + pr.path.last
    )

  }

  override def generatedSourcesPathsBuilder: T[Seq[(String, String)]] = Task {
    Seq("@generated/*" -> "typescript/generatedSources")
  }

  override def upstreamPathsBuilder: Task[Seq[(String, String)]] = Task.Anon {

    val upstreams = (for {
      (res, mod) <- Task.traverse(moduleDeps)(_.resources)().zip(moduleDeps)
    } yield {
      val relative = mod.millSourcePath.subRelativeTo(Task.workspace)
      Seq((
        mod.millSourcePath.subRelativeTo(Task.workspace).toString + "/*",
        s"typescript/$relative/src:${pubDeclarationOut()}"
      )) ++
        res.map { rp =>
          val resourceRoot = rp.path.last
          val modName = mod.millSourcePath.subRelativeTo(Task.workspace).toString
          // nb: resources are be moved in bundled stage
          (
            s"@$modName/$resourceRoot/*",
            resourceRoot match {
              case s if s.contains(".dest") =>
                rp.path.toString
              case _ => s"typescript/$modName/$resourceRoot"
            }
          )
        }

    }).flatten

    upstreams
  }

  override def typeRoots: T[ujson.Value] = Task {
    ujson.Arr(
      "node_modules/@types",
      "declarations"
    )
  }

  override def declarationDir: T[ujson.Value] = Task {
    ujson.Str("declarations")
  }

  override def compilerOptionsPaths: Task[Map[String, String]] =
    Task.Anon { Map.empty[String, String] }

  override def compilerOptions: T[Map[String, ujson.Value]] = Task {
    Map(
      "declarationMap" -> ujson.Bool(true),
      "esModuleInterop" -> ujson.Bool(true),
      "baseUrl" -> ujson.Str("."),
      "rootDir" -> ujson.Str("typescript"),
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

  // patch typescript
  private def pubTsPatchInstall: T[Unit] = Task {
    os.call(
      ("node", npmInstall().path / "node_modules/ts-patch/bin/ts-patch", "install"),
      cwd = npmInstall().path
    )
    ()
  }

  private def pubSymLink: Task[Unit] = Task {
    pubTsPatchInstall() // patch typescript compiler => use custom transformers
    os.symlink(publishDir().path / "node_modules", npmInstall().path / "node_modules")

    if (os.exists(npmInstall().path / ".npmrc"))
      os.symlink(publishDir().path / ".npmrc", npmInstall().path / ".npmrc")
  }

  override def compile: T[(PathRef, PathRef)] = Task {
    pubSymLink()
    os.write(
      publishDir().path / "tsconfig.json",
      ujson.Obj(
        "compilerOptions" -> ujson.Obj.from(
          compilerOptionsBuilder().toSeq ++ Seq("typeRoots" -> typeRoots())
        ),
        "files" -> pubAllSources()
      )
    )
    os.copy(millSourcePath, publishDir().path / "typescript", mergeFolders = true)
    pubCopyModDeps()
    pubGenSources()
    // Run type check, build declarations
    os.call(
      ("node", npmInstall().path / "node_modules/typescript/bin/tsc"),
      cwd = publishDir().path
    )
    (publishDir(), PathRef(publishDir().path / "typescript"))
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
              publishDir().path.toString + "/" + pubBundledOut() + "/" + rp.last
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

  override def bundle: T[PathRef] = Task {
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
      cwd = compile()._1.path
    )
    PathRef(bundle)
  }

  // EsBuild - END

  // publishDir; is used to process and compile files for publishing
  def publishDir: T[PathRef] = Task { PathRef(Task.dest) }

  def publish(): Command[Unit] = Task.Command {
    // build package.json
    os.move(pubbPackageJson().path / "package.json", publishDir().path / "package.json")

    // bundle code for publishing
    bundle()

    // run npm publish
    os.call(("npm", "publish"), stdout = os.Inherit, cwd = publishDir().path)
    ()
  }

}

object PublishModule {
  case class PublishMeta(
      name: String,
      version: String,
      description: String,
      main: String = "",
      types: String = "",
      author: String = "",
      license: mill.scalalib.publish.License = mill.scalalib.publish.License.MIT,
      homepage: String = "",
      bin: Map[String, String] = Map.empty[String, String],
      files: Seq[String] = Seq.empty[String],
      scripts: Map[String, String] = Map.empty[String, String],
      engines: Map[String, String] = Map.empty[String, String],
      keywords: Seq[String] = Seq.empty[String],
      repository: Repository = EmptyRepository,
      bugs: Bugs = EmptyBugs,
      dependencies: Map[String, String] = Map.empty[String, String],
      devDependencies: Map[String, String] = Map.empty[String, String],
      publishConfig: PublishConfig = EmptyPubConfig,
      exports: Map[String, ExportEntry] = Map.empty[String, ExportEntry],
      typesVersions: Map[String, Seq[String]] = Map.empty[String, Seq[String]]
  ) {
    def toJson: ujson.Value = ujson.Obj(
      "name" -> name,
      "version" -> version,
      "description" -> description,
      "main" -> main,
      "types" -> types,
      "files" -> ujson.Arr.from(files),
      "scripts" -> ujson.Obj.from(scripts.map { case (k, v) => k -> ujson.Str(v) }),
      "bin" -> ujson.Obj.from(bin.map { case (k, v) => k -> ujson.Str(v) }),
      "engines" -> ujson.Obj.from(engines.map { case (k, v) => k -> ujson.Str(v) }),
      "keywords" -> ujson.Arr.from(keywords),
      "author" -> author,
      "license" -> license.id,
      "repository" -> repository.toJson,
      "bugs" -> bugs.toJson,
      "homepage" -> homepage,
      "dependencies" -> ujson.Obj.from(dependencies.map { case (k, v) => k -> ujson.Str(v) }),
      "devDependencies" -> ujson.Obj.from(devDependencies.map { case (k, v) => k -> ujson.Str(v) }),
      "publishConfig" -> publishConfig.toJson,
      "exports" -> ujson.Obj.from(exports.map { case (key, value) => key -> value.toJson }),
      "typesVersions" -> ujson.Obj((
        "*",
        ujson.Obj.from(typesVersions.map { case (k, v) => k -> ujson.Arr.from(v) })
      ))
    )

    def toJsonClean: ujson.Value = removeEmptyValues(toJson)
  }

  object PublishMeta {
    implicit val rw: upickle.default.ReadWriter[PublishMeta] = upickle.default.macroRW
  }

  case class Repository(`type`: String, url: String) {
    def toJson: ujson.Value = ujson.Obj(
      "type" -> `type`,
      "url" -> url
    )
  }

  object Repository {
    implicit val rw: upickle.default.ReadWriter[Repository] = upickle.default.macroRW
  }

  object EmptyRepository extends Repository("", "") {
    override def toJson: ujson.Value = ujson.Obj()
  }

  case class Bugs(url: String, email: Option[String]) {
    def toJson: ujson.Value = {
      val base = ujson.Obj("url" -> url)
      email.foreach(e => base("email") = e)
      base
    }
  }

  object EmptyBugs extends Bugs("", None) {
    override def toJson: ujson.Value = ujson.Obj()
  }

  object Bugs {
    implicit val rw: upickle.default.ReadWriter[Bugs] = upickle.default.macroRW
  }

  case class PublishConfig(registry: String, access: String) {
    def toJson: ujson.Value = ujson.Obj(
      "registry" -> registry,
      "access" -> access
    )
  }

  object EmptyPubConfig extends PublishConfig("", "") {
    override def toJson: ujson.Value = ujson.Obj()
  }

  object PublishConfig {
    implicit val rw: upickle.default.ReadWriter[PublishConfig] = upickle.default.macroRW
  }

  sealed trait ExportEntry {
    def toJson: ujson.Value
  }

  object ExportEntry {
    implicit val rw: upickle.default.ReadWriter[ExportEntry] = upickle.default.macroRW
  }

  case class Export(path: String) extends ExportEntry {
    def toJson: ujson.Value = ujson.Str(path)
  }

  object Export {
    implicit val rw: upickle.default.ReadWriter[Export] = upickle.default.macroRW
  }

  case class ExportConditions(conditions: Map[String, ExportEntry]) extends ExportEntry {
    def toJson: ujson.Value = ujson.Obj.from(conditions.map { case (key, value) =>
      key -> value.toJson
    })
  }

  object ExportConditions {
    implicit val rw: upickle.default.ReadWriter[ExportConditions] = upickle.default.macroRW
  }

  private def removeEmptyValues(json: ujson.Value): ujson.Value = {
    json match {
      case obj: ujson.Obj =>
        val filtered = obj.value.filterNot { case (_, v) => isEmptyValue(v) }
        val transformed = filtered.map { case (k, v) => k -> removeEmptyValues(v) }
        if (transformed.isEmpty) ujson.Null else ujson.Obj.from(transformed)
      case arr: ujson.Arr =>
        val filtered = arr.value.filterNot(isEmptyValue)
        val transformed = filtered.map(removeEmptyValues)
        if (transformed.isEmpty) ujson.Null else ujson.Arr(transformed)
      case str: ujson.Str if str.value.isEmpty => ujson.Null // Added to remove empty strings
      case other => other
    }
  }

  private def isEmptyValue(json: ujson.Value): Boolean = {
    json match {
      case ujson.Str("") | ujson.Null => true
      case _: ujson.Obj | _: ujson.Arr => removeEmptyValues(json) == ujson.Null // crucial check
      case _ => false
    }
  }

}
