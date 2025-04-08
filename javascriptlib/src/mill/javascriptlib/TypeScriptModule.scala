package mill.javascriptlib

import mill.*
import os.*

import scala.annotation.tailrec
import scala.util.Try
import mill.scalalib.publish.licenseFormat

trait TypeScriptModule extends Module { outer =>
  // custom module names
  def moduleName: String = super.toString

  override def toString: String = moduleName

  def moduleDeps: Seq[TypeScriptModule] = Nil

  // recursively retrieve dependecies of all module dependencies
  def recModuleDeps: Seq[TypeScriptModule] = {
    @tailrec
    def recModuleDeps_(
        t: Seq[TypeScriptModule],
        acc: Seq[TypeScriptModule]
    ): Seq[TypeScriptModule] = {
      if (t.isEmpty) acc
      else {
        val currentMod = t.head
        val cmModDeps = currentMod.moduleDeps
        recModuleDeps_(t.tail ++ cmModDeps, cmModDeps ++ acc)
      }
    }

    recModuleDeps_(moduleDeps, moduleDeps).distinct
  }

  def npmDeps: T[Seq[String]] = Task { Seq.empty[String] }

  def enableEsm: T[Boolean] = Task { false }

  def npmDevDeps: T[Seq[String]] = Task { Seq.empty[String] }

  def unmanagedDeps: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }

  def transitiveNpmDeps: T[Seq[String]] = Task {
    Task.traverse(moduleDeps)(_.npmDeps)().flatten ++ npmDeps()
  }

  def transitiveNpmDevDeps: T[Seq[String]] =
    Task.traverse(moduleDeps)(_.npmDevDeps)().flatten ++ npmDevDeps()

  def transitiveUnmanagedDeps: T[Seq[PathRef]] =
    Task.traverse(moduleDeps)(_.unmanagedDeps)().flatten ++ unmanagedDeps()

  /**
   * Typescript versioning:
   *  - typescript
   *  - ts-node
   *  - tsconfig-paths
   *  - @types/node
   */
  def tsDeps: T[Seq[String]] = Task {
    Seq(
      "@types/node@22.10.9",
      "typescript@5.7.3",
      "ts-node@^10.9.2",
      "tsconfig-paths@4.2.0"
    )
  }

  /**
   * Mill will use `esbuild` alongside `ts-node` to bundle Ts project,
   * to use a custom build tool, you can simply over-ride the default bundle implementation.
   *
   * Default bundle versioning:
   * - esbuild
   * - esbuild-plugin-copy
   * - esbuild-plugins/tsconfig-paths
   * - esbuild-copy-static-files
   * - @types/esbuild-copy-static-files
   */
  private def bundleDeps: T[Seq[String]] = Task {
    Seq(
      "@types/esbuild-copy-static-files@0.1.4",
      "esbuild@0.24.2",
      "esbuild-plugin-copy@2.1.1",
      "@esbuild-plugins/tsconfig-paths@0.1.2",
      "esbuild-copy-static-files@0.1.0"
    )
  }

  def npmInstall: T[PathRef] = Task {
    Try(os.copy.over(Task.workspace / ".npmrc", Task.dest / ".npmrc")).getOrElse(())

    // build package.json with
    mkPackageJson()

    os.call((
      "npm",
      "install",
      "--prefix",
      ".",
      "--userconfig",
      ".npmrc",
      "--save-dev",
      transitiveUnmanagedDeps().map(_.path.toString)
    ))
    PathRef(Task.dest)
  }

  // sources :)
  def sources: T[Seq[PathRef]] = Task.Sources("src")

  def resources: T[Seq[PathRef]] = Task { Seq(PathRef(moduleDir / "resources")) }

  def generatedSources: T[Seq[PathRef]] = Task { Seq[PathRef]() }

  private def tscModDepsResources: T[Seq[(PathRef, Seq[PathRef])]] =
    Task
      .traverse(recModuleDeps)(_.resources)()
      .zip(recModuleDeps)
      .map { case (r, m) => (PathRef(m.moduleDir), r) }

  private def tscModDepsSources: T[Seq[(PathRef, Seq[PathRef])]] =
    Task
      .traverse(recModuleDeps)(_.sources)()
      .zip(recModuleDeps)
      .map { case (s, m) => (PathRef(m.moduleDir), s) }

  private def tscCoreGenSources: T[Seq[PathRef]] = Task {
    for {
      pr <- generatedSources()
      file <- os.walk(pr.path)
      if file.ext == "ts"
    } yield PathRef(file)
  }

  private def tscModDepsGenSources: T[Seq[(PathRef, Seq[PathRef])]] =
    Task
      .traverse(recModuleDeps)(_.generatedSources)()
      .zip(recModuleDeps)
      .map { case (s, m) =>
        (
          PathRef(m.moduleDir),
          s.flatMap { genS => os.walk(genS.path).filter(_.ext == "ts").map(PathRef(_)) }
        )
      }

  def tscCopySources: Task[Unit] = Task.Anon {
    val coreTarget = T.dest / "src"

    if (!os.exists(T.dest)) os.makeDir.all(T.dest)

    // Copy everything except "build.mill" and the "/out" directory from Task.workspace
    os.walk(moduleDir, skip = _.last == "out")
      .filter(_.last != "build.mill")
      .filter(_.last != "mill")
      .filter(_.last != "package.json")
      .filter(_.last != "package-lock.json")
      .filter(_.last != "tsconfig.json")
      .foreach { path =>
        val relativePath = path.relativeTo(moduleDir)
        val destination = T.dest / relativePath

        if (os.isDir(path)) os.makeDir.all(destination)
        else os.copy.over(path, destination)
      }

    object IsSrcDirectory {
      def unapply(path: Path): Option[Path] =
        if (os.isDir(path) && path.last == "src") Some(path) else None
    }

    // handle copy `/out/<mod>/<source.dest>/src` directories
    def copySrcDirectory(srcDir: Path, targetDir: Path): Unit = {
      os.list(srcDir).foreach { srcFile =>
        os.copy.over(srcFile, targetDir / srcFile.last, createFolders = true)
      }
    }

    //  handle sources generated in /out (eg: `out/<mod>/sources.dest`)
    def copyOutSources(sources: Seq[PathRef], target: Path): Unit = {

      def copySource(source: PathRef): Unit = {
        if (!source.path.startsWith(Task.workspace / "out")) () // Guard clause
        else os.list(source.path).foreach {
          case IsSrcDirectory(srcDir) => copySrcDirectory(srcDir, target)
          case path => os.copy.over(path, target / path.last, createFolders = true)
        }
      }

      sources.foreach(copySource)
    }

    // core
    copyOutSources(sources(), coreTarget)

    // mod deps
    tscModDepsSources()
      .foreach { case (mod, sources_) =>
        copyOutSources(sources_, T.dest / mod.path.relativeTo(Task.workspace) / "src")
      }

  }

  private[javascriptlib] def tscCopyModDeps: Task[Unit] = Task.Anon {
    val targets =
      recModuleDeps.map { _.moduleDir.subRelativeTo(Task.workspace).segments.head }.distinct

    targets.foreach { target =>
      val destination = T.dest / target
      os.makeDir.all(destination / os.up)
      os.copy(
        Task.workspace / target,
        destination,
        mergeFolders = true
      )
    }
  }

  // mv generated sources for base mod and its deps
  private[javascriptlib] def tscCopyGenSources: Task[Unit] = Task.Anon {
    def copyGeneratedSources(sourcePath: os.Path, destinationPath: os.Path): Unit = {
      os.makeDir.all(destinationPath / os.up)
      os.copy.over(sourcePath, destinationPath)
    }

    tscCoreGenSources().foreach { target =>
      val destination = T.dest / "generatedSources" / target.path.last
      copyGeneratedSources(target.path, destination)
    }

    tscModDepsGenSources().foreach { case (mod, source_) =>
      source_.foreach { target =>
        val modDir = mod.path.relativeTo(Task.workspace)
        val destination = T.dest / modDir / "generatedSources" / target.path.last
        copyGeneratedSources(target.path, destination)
      }
    }
  }

  /**
   * Link all external resources eg: `out/<mod>/resources.dest`
   * to `moduleDir / src / resources`
   */
  private[javascriptlib] def tscLinkResources: Task[Unit] = Task.Anon {
    val dest = T.dest / "resources"
    if (!os.exists(dest)) os.makeDir.all(dest)

    val externalResource: PathRef => Boolean = p =>
      p.path.startsWith(Task.workspace / "out") &&
        os.exists(p.path) &&
        os.isDir(p.path)

    def linkResource(resources_ : Seq[PathRef], dest: Path): Unit = {
      resources_
        .filter(externalResource)
        .flatMap(p => os.list(p.path)) // Get all items from valid directories
        .foreach(item => os.copy.over(item, dest / item.last, createFolders = true))
    }

    linkResource(resources(), dest)

    tscModDepsResources().foreach { case (mod, r) =>
      val modDir = mod.path.relativeTo(Task.workspace)
      val modDest = T.dest / modDir / "resources"
      if (!os.exists(modDest)) os.makeDir.all(modDest)
      linkResource(r, modDest)
    }
  }

  def tscAllSources: T[IndexedSeq[String]] = Task {
    val fileExt: Path => Boolean = _.ext == "ts"

    def relativeToTS(base: Path, path: Path, prefix: Option[String] = None): Option[String] =
      prefix match {
        case Some(value) => Some(s"$value/${path.relativeTo(base)}")
        case None => Some(s"${path.relativeTo(base)}")
      }

    def handleOutTS(base: Path, path: Path, prefix: Option[String] = None): Option[String] = {
      val segments = path.relativeTo(base).segments
      val externalSourceDir = base / segments.head
      prefix match {
        case Some(_) => relativeToTS(externalSourceDir, path, prefix)
        case None => relativeToTS(externalSourceDir, path)
      }
    }

    def relativeToTypescript(base: Path, path: Path, prefix: String): Option[String] =
      Some(s"$prefix/${path.relativeTo(base)}")

    def handleOutPath(base: Path, path: Path, prefix: String): Option[String] = {
      val segments = path.relativeTo(base).segments
      val externalSourceDir = base / segments.head
      relativeToTypescript(externalSourceDir, path, prefix)
    }

    val cores = sources()
      .toIndexedSeq
      .flatMap(pr => if (isDir(pr.path)) os.walk(pr.path) else Seq(pr.path))
      .filter(fileExt)
      .flatMap { p =>
        p match {
          case _ if p.startsWith(moduleDir) && !p.startsWith(moduleDir / "out") =>
            relativeToTS(moduleDir, p)
          case _ if p.startsWith(Task.workspace / "out" / moduleName) =>
            handleOutTS(Task.workspace / "out" / moduleName, p)
          case _ if p.startsWith(Task.workspace / "out") =>
            handleOutTS(Task.workspace / "out", p)
          case _ => None
        }
      }

    val modDeps = tscModDepsSources()
      .toIndexedSeq
      .flatMap { case (mod, source_) =>
        source_
          .flatMap(pr => if (isDir(pr.path)) os.walk(pr.path) else Seq(pr.path))
          .filter(fileExt)
          .flatMap { p =>
            val modDir = mod.path.relativeTo(Task.workspace)
            val modmoduleDir = Task.workspace / modDir
            val modOutPath = Task.workspace / "out" / modDir

            p match {
              case _ if p.startsWith(modmoduleDir) =>
                relativeToTypescript(modmoduleDir, p, modDir.toString)
              case _ if p.startsWith(modOutPath) =>
                handleOutPath(modOutPath, p, modDir.toString)
              case _ => None
            }

          }
      }

    val coreGenSources = tscCoreGenSources()
      .toIndexedSeq
      .map(pr => "generatedSources/" + pr.path.last)

    val modGenSources = tscModDepsGenSources()
      .toIndexedSeq
      .flatMap { case (mod, source_) =>
        val modDir = mod.path.relativeTo(Task.workspace)
        source_.map(s"$modDir/generatedSources/" + _.path.last)
      }

    cores ++ modDeps ++ coreGenSources ++ modGenSources

  }

  // sources

  // compile :)
  def declarationDir: T[String] = Task { "declarations" }

  // specify tsconfig.compilerOptions
  def compilerOptions: T[Map[String, ujson.Value]] = Task {
    Map(
      "skipLibCheck" -> ujson.Bool(true),
      "esModuleInterop" -> ujson.Bool(true),
      "declaration" -> ujson.Bool(true),
      "emitDeclarationOnly" -> ujson.Bool(true),
      "baseUrl" -> ujson.Str("."),
      "rootDir" -> ujson.Str(".")
    ) ++ Seq(
      if (enableEsm()) Some("module" -> ujson.Str("nodenext")) else None,
      if (enableEsm()) Some("moduleResolution" -> ujson.Str("nodenext")) else None
    ).flatten
  }

  // specify tsconfig.compilerOptions.Paths
  def compilerOptionsPaths: T[Map[String, String]] = Task { Map.empty[String, String] }

  def upstreamPathsBuilder: T[Seq[(String, String)]] = Task {
    val upstreams = (for {
      (res, mod) <- Task.traverse(recModuleDeps)(_.resources)().zip(recModuleDeps)
    } yield {
      val prefix = mod.moduleName.replaceAll("\\.", "/")
      val customResource: PathRef => Boolean = pathRef =>
        pathRef.path.startsWith(Task.workspace / "out" / mod.moduleName) || !pathRef.path.equals(
          mod.moduleDir / "src" / "resources"
        )

      val customResources = res
        .filter(customResource)
        .map { pathRef =>
          val resourceRoot = pathRef.path.last
          s"@$prefix/$resourceRoot/*" -> s"$prefix/$resourceRoot"
        }

      Seq(
        (
          prefix + "/*",
          s"$prefix/src" + ":" + s"declarations/$prefix"
        ),
        (s"@$prefix/resources/*", s"$prefix/resources")
      ) ++ customResources

    }).flatten

    upstreams
  }

  def modulePaths: T[Seq[(String, String)]] = Task {
    val customResource: PathRef => Boolean = pathRef =>
      pathRef.path.startsWith(Task.workspace / "out") || !pathRef.path.equals(
        moduleDir / "src" / "resources"
      )

    val customResources = resources()
      .filter(customResource)
      .map { pathRef =>
        val resourceRoot = pathRef.path.last
        s"@$moduleName/$resourceRoot/*" -> s"$resourceRoot"
      }

    Seq(
      (s"$moduleName/*", "src" + ":" + declarationDir()),
      (s"@$moduleName/resources/*", "resources")
    ) ++ customResources
  }

  def typeRoots: Task[ujson.Value] = Task.Anon {
    ujson.Arr(
      "node_modules/@types",
      declarationDir()
    )
  }

  def generatedSourcesPathsBuilder: T[Seq[(String, String)]] = Task {
    Seq(("@generated/*", "generatedSources"))
  }

  def compilerOptionsBuilder: Task[Map[String, ujson.Value]] = Task.Anon {
    val combinedPaths =
      upstreamPathsBuilder() ++
        generatedSourcesPathsBuilder() ++
        modulePaths() ++
        compilerOptionsPaths().toSeq

    val combinedCompilerOptions: Map[String, ujson.Value] = compilerOptions() ++ Map(
      "declarationDir" -> ujson.Str(declarationDir()),
      "paths" -> ujson.Obj.from(combinedPaths.map { case (k, v) =>
        val splitValues =
          v.split(":").map(s => s"$s/*") // Split by ":" and append "/*" to each part
        (k, ujson.Arr.from(splitValues))
      })
    )

    combinedCompilerOptions
  }

  /**
   * create a createNodeModulesSymlink for node_modules in compile.dest
   * removes need for node_modules prefix in import statements `node_modules/<some-package>`
   * import * as somepackage from "<some-package>"
   */
  def createNodeModulesSymlink: Task[Unit] = Task.Anon {
    os.copy.over(npmInstall().path / "package.json", T.dest / "package.json")

    if (!os.exists(T.dest / "node_modules"))
      os.symlink(T.dest / "node_modules", npmInstall().path / "node_modules")

    if (!os.exists(T.dest / "package-lock.json"))
      os.symlink(T.dest / "package-lock.json", npmInstall().path / "package-lock.json")
  }

  /**
   * Run `ts-node` command if set to `true`.
   * `ts-node` will build declarations and or js output, depending on ts-config.
   */
  def runTypeCheck: T[Boolean] = Task { true }

  /**
   * Mill by default will use local tsconfig if one is provided,
   * This can set to `false` if you would prefer mill to use the generated
   * tsconfig, if you can not for some reason just not delete the local one.
   *
   * Regardless of the configuration, mill will auto gen a tsconfig
   * if one does not exist in `T.workspace`.
   */
  def customTsConfig: T[Boolean] = Task { true }

  private[javascriptlib] def mkTsconfig: Task[Unit] = Task.Anon {
    val default: Map[String, ujson.Value] = Map(
      "compilerOptions" -> ujson.Obj.from(
        compilerOptionsBuilder().toSeq ++ Seq("typeRoots" -> typeRoots())
      ),
      "files" -> tscAllSources()
    )

    os.write.over(
      T.dest / "tsconfig.json",
      ujson.Obj.from(default.toSeq ++ options().toSeq)
    )

  }

  private def mkPackageJson: Task[Unit] = Task.Anon {
    val packageJson = packageJsonWithDefaults()

    val packageJsonClean = packageJson
      .copy(`type` = if (enableEsm()) "module" else packageJson.`type`)
      .cleanJson

    os.write.over(Task.dest / "package.json", packageJsonClean.render(2))
  }

  def compile: T[PathRef] = Task {
    tscCopySources()
    tscCopyModDeps()
    tscCopyGenSources()
    tscLinkResources()
    createNodeModulesSymlink()
    mkTsconfig()

    // Run type check, build declarations
    if (runTypeCheck())
      os.call("node_modules/typescript/bin/tsc", cwd = T.dest)

    PathRef(T.dest)
  }

  // compile

  // additional ts-config options
  def options: T[Map[String, ujson.Value]] = Task {
    Seq(
      Some("exclude" -> ujson.Arr.from(Seq("node_modules", "**/node_modules/*"))),
      if (enableEsm()) Some("ts-node" -> ujson.Obj("esm" -> ujson.True, "swc" -> ujson.True))
      else None
    ).flatten.toMap: Map[String, ujson.Value]
  }

  // Execution :)

  def mainFileName: T[String] = Task { s"$moduleName.ts" }

  def mainFilePath: T[Path] = Task { compile().path / "src" / mainFileName() }

  def forkEnv: T[Map[String, String]] = Task { Map.empty[String, String] }

  def computedArgs: T[Seq[String]] = Task { Seq.empty[String] }

  def executionFlags: T[Map[String, String]] = Task { Map.empty[String, String] }

  def run(args: mill.define.Args): Command[CommandResult] = Task.Command {
    val mainFile = mainFilePath()
    val env = forkEnv()

    val tsnode: String =
      if (enableEsm()) "ts-node/esm"
      else (npmInstall().path / "node_modules/.bin/ts-node").toString

    val tsconfigPaths: Seq[String] =
      Seq(
        if (enableEsm()) Some("tsconfig-paths/register")
        else Some((npmInstall().path / "node_modules/tsconfig-paths/register").toString),
        if (enableEsm()) Some("--no-warnings=ExperimentalWarning") else None
      ).flatten

    val flags: Seq[String] =
      (executionFlags()
        .map {
          case (key, "") => Some(s"--$key")
          case (key, value) => Some(s"--$key=$value")
          case _ => None
        }.toSeq ++ Seq(if (enableEsm()) Some("--loader") else None)).flatten

    val runnable: Shellable = (
      "node",
      flags,
      tsnode,
      "-r",
      tsconfigPaths,
      mainFile,
      computedArgs(),
      args.value
    )

    os.call(
      runnable,
      stdout = os.Inherit,
      env = env,
      cwd = compile().path
    )
  }

  // Execution

  // bundle :)

  def bundleExternal: T[Seq[ujson.Value]] = Task { Seq(ujson.Str("fs"), ujson.Str("path")) }

  def bundleFlags: T[Map[String, ujson.Value]] = Task {
    Map(
      "entryPoints" -> ujson.Arr(mainFilePath().toString),
      "bundle" -> ujson.Bool(true),
      "platform" -> ujson.Str("node")
    )
  }

  /**
   * configure esbuild with `@esbuild-plugins/tsconfig-paths`
   * include .d.ts files
   */
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
             |      dest: ${ujson.Str(Task.dest.toString + "/" + rp.last)},
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
    }.mkString(",\n")

    s"""|import * as esbuild from 'esbuild';
        |import TsconfigPathsPlugin from '@esbuild-plugins/tsconfig-paths'
        |import copyStaticFiles from 'esbuild-copy-static-files';
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
    val bundle = Task.dest / "bundle.js"
    val out = compile().path

    os.walk(out)
      .foreach(p => os.copy.over(p, T.dest / p.relativeTo(out), createFolders = true))

    os.write(
      T.dest / "build.ts",
      bundleScriptBuilder()
    )

    os.call(
      (tsnode, T.dest / "build.ts"),
      stdout = os.Inherit,
      env = env,
      cwd = T.dest
    )
    PathRef(bundle)
  }

  // bundle

  // package.json; package-meta
  private def packageJsonWithDefaults: T[TypeScriptModule.PackageJson] = Task {
    def splitDeps(input: String): (String, ujson.Str) = input match {
      case s if s.startsWith("@") =>
        val withoutAt = s.drop(1) // Remove leading @
        val parts = withoutAt.split("@", 2) // Split on the first '@' in the rest
        ("@" + parts(0), parts.lift(1).getOrElse("")) // Re-add '@' to the first part

      case _ =>
        val parts = input.split("@", 2) // Regular case, split on the first '@'
        (parts(0), parts.lift(1).getOrElse("")) // Handle case where '@' is missing
    }

    val json = packageJson()

    json.copy(
      name = if (json.name.nonEmpty) json.name else moduleName,
      version = if (json.version.nonEmpty) json.version else "1.0.0",
      dependencies = ujson.Obj.from(transitiveNpmDeps().map(splitDeps)),
      devDependencies = ujson.Obj.from((
        transitiveNpmDevDeps() ++
          tsDeps() ++
          bundleDeps() ++
          Seq(if (enableEsm()) Some("@swc/core@1.10.12") else None).flatten
      ).map(splitDeps))
    )
  }

  def packageJson: T[TypeScriptModule.PackageJson] = Task { PackageJson() }

  // test methods :)

  private[javascriptlib] def coverageDirs: T[Seq[String]] = Task { Seq.empty[String] }

  private[javascriptlib] def outerModuleName: Option[String] = None

  trait TypeScriptTests extends TypeScriptModule {
    override def moduleDeps: Seq[TypeScriptModule] = Seq(outer) ++ outer.moduleDeps

    override def outerModuleName: Option[String] = Some(outer.moduleName)

    override def sources: T[Seq[PathRef]] = Task.Sources(moduleDir)

    def allSources: T[IndexedSeq[PathRef]] =
      Task {
        val fileExt: Path => Boolean = _.ext == "ts"
        sources()
          .toIndexedSeq
          .flatMap(pr => os.walk(pr.path))
          .filter(fileExt)
          .map(PathRef(_))
      }

    def testResourcesPath: T[Seq[(String, String)]] = Task {
      Seq((
        "@test/resources/*",
        s"test/resources"
      ))
    }

    override def compilerOptionsBuilder: T[Map[String, ujson.Value]] = Task {
      val combinedPaths =
        outer.upstreamPathsBuilder() ++
          upstreamPathsBuilder() ++
          outer.generatedSourcesPathsBuilder() ++
          outer.modulePaths() ++
          outer.compilerOptionsPaths().toSeq ++
          testResourcesPath()

      val combinedCompilerOptions: Map[String, ujson.Value] =
        outer.compilerOptions() ++ compilerOptions() ++ Map(
          "declarationDir" -> ujson.Str(outer.declarationDir()),
          "paths" -> ujson.Obj.from(combinedPaths.map { case (k, v) =>
            val splitValues =
              v.split(":").map(s => s"$s/*") // Split by ":" and append "/*" to each part
            (k, ujson.Arr.from(splitValues))
          })
        )

      combinedCompilerOptions
    }

    override def compile: T[PathRef] = Task {
      val out = outer.compile()

      val files: IndexedSeq[String] =
        allSources()
          .map(x => "test/" + x.path.relativeTo(moduleDir)) ++
          outer.tscAllSources()

      // mv compile<outer> to compile<test>
      os.list(out.path)
        .filter(item =>
          item.last != "tsconfig.json" &&
            item.last != "package-lock.json" &&
            !(item.last == "node_modules" && os.isDir(
              item
            ))
        )
        .foreach(item => os.copy.over(item, T.dest / item.last, createFolders = true))

      // inject test specific tsconfig into <outer> tsconfig
      os.write(
        Task.dest / "tsconfig.json",
        ujson.Obj(
          "compilerOptions" -> ujson.Obj.from(
            compilerOptionsBuilder().toSeq ++ Seq("typeRoots" -> outer.typeRoots())
          ),
          "files" -> files
        )
      )

      PathRef(T.dest)
    }

    override def npmInstall: T[PathRef] = Task {
      os.call(
        (
          "npm",
          "install",
          "--prefix",
          ".",
          "--userconfig",
          ".npmrc",
          "--save-dev",
          transitiveNpmDeps(),
          transitiveNpmDevDeps(),
          transitiveUnmanagedDeps().map(_.path.toString)
        ),
        cwd = outer.npmInstall().path
      )
      outer.npmInstall()
    }

    override private[javascriptlib] def coverageDirs: T[Seq[String]] =
      Task { outer.tscAllSources() }

  }

}

object TypeScriptModule {
  case class PackageJson(
      name: String = "",
      version: String = "",
      description: String = "",
      main: String = "",
      module: String = "",
      `type`: String = "",
      types: String = "",
      author: String = "",
      license: mill.scalalib.publish.License = mill.scalalib.publish.License.MIT,
      homepage: String = "",
      bin: ujson.Obj = ujson.Obj(),
      files: ujson.Arr = Seq.empty[String],
      scripts: ujson.Obj = ujson.Obj(),
      engines: ujson.Obj = ujson.Obj(),
      keywords: ujson.Arr = Seq.empty[String],
      repository: ujson.Obj = ujson.Obj(),
      bugs: ujson.Obj = ujson.Obj(),
      dependencies: ujson.Obj = ujson.Obj(),
      devDependencies: ujson.Obj = ujson.Obj(),
      publishConfig: ujson.Obj = ujson.Obj(),
      exports: ujson.Obj = ujson.Obj(),
      typesVersions: ujson.Obj = ujson.Obj(),
      `private`: Boolean = false,
      peerDependencies: ujson.Obj = ujson.Obj(),
      optionalDependencies: ujson.Obj = ujson.Obj(),
      overrides: ujson.Obj = ujson.Obj(),
      funding: ujson.Obj = ujson.Obj(),
      contributors: ujson.Arr = Seq.empty[String],
      sideEffects: Boolean = false,
      resolutions: ujson.Obj = ujson.Obj()
  ) {
    def cleanJson: ujson.Value = removeEmptyValues(ujson.Obj(
      "name" -> name,
      "version" -> version,
      "description" -> description,
      "main" -> main,
      "module" -> module,
      "type" -> `type`,
      "types" -> types,
      "files" -> files,
      "scripts" -> scripts,
      "bin" -> bin,
      "engines" -> engines,
      "keywords" -> keywords,
      "author" -> author,
      "license" -> license.id,
      "repository" -> repository,
      "bugs" -> bugs,
      "homepage" -> homepage,
      "dependencies" -> dependencies,
      "devDependencies" -> devDependencies,
      "publishConfig" -> publishConfig,
      "exports" -> exports,
      "typesVersions" -> typesVersions,
      "private" -> `private`,
      "peerDependencies" -> peerDependencies,
      "optionalDependencies" -> optionalDependencies,
      "overrides" -> overrides,
      "funding" -> funding,
      "contributors" -> contributors,
      "sideEffects" -> sideEffects,
      "resolutions" -> resolutions
    ))
  }

  object PackageJson {
    implicit val rw: upickle.default.ReadWriter[PackageJson] = upickle.default.macroRW
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
