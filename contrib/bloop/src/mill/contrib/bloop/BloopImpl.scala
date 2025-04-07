//package mill.contrib.bloop
//
//import _root_.bloop.config.{Config => BloopConfig, Tag => BloopTag}
//import mill._
//import mill.api.Result
//import mill.define.{Discover, Evaluator, ExternalModule, Module as MillModule}
//import mill.main.BuildInfo
//import mill.scalalib.internal.JavaModuleUtils
//import mill.scalajslib.ScalaJSModule
//import mill.scalajslib.api.{JsEnvConfig, ModuleKind}
//import mill.scalalib._
//import mill.scalanativelib.ScalaNativeModule
//import mill.scalanativelib.api.ReleaseMode
//
//import java.nio.file.{FileSystemNotFoundException, Files, Paths}
//
///**
// * Implementation of the Bloop related tasks. Inherited by the
// * `mill.contrib.bloop.Bloop` object, and usable in tests by passing
// * a custom evaluator.
// */
//class BloopImpl(
//    evs: () => Seq[Evaluator],
//    wd: os.Path,
//    addMillSources: Option[Boolean]
//) extends ExternalModule {
//  outer =>
//  import BloopFormats._
//
//  private val bloopDir = wd / ".bloop"
//
//  /**
//   * Generates bloop configuration files reflecting the build,
//   * under pwd/.bloop.
//   */
//  def install() = Task.Command {
//    val res = Task.traverse(computeModules) {
//      case (mod, isRoot) =>
//        mod.bloop.writeConfigFile(isRoot)
//    }()
//    val written = res.map(_._2).map(_.path)
//    // Make bloopDir if it doesn't exists
//    if (!os.exists(bloopDir)) {
//      os.makeDir(bloopDir)
//    }
//    // Cleaning up configs that weren't generated in this run.
//    os.list(bloopDir)
//      .filter(_.ext == "json")
//      .filterNot(written.contains)
//      .foreach(os.remove)
//    res
//  }
//
//  /**
//   * Trait that can be mixed-in to quickly access the bloop config
//   * of the module.
//   *
//   * {{{
//   * object myModule extends ScalaModule with Bloop.Module {
//   *    ...
//   * }
//   * }}}
//   */
//  trait Module extends MillModule { self: JavaModule =>
//
//    /**
//     * Allows to tell Bloop whether it should use "fullOptJs" or
//     * "fastOptJs" when compiling. Used exclusively with ScalaJsModules.
//     */
//    def linkerMode: T[Option[BloopConfig.LinkerMode]] = None
//
//    object bloop extends MillModule {
//      def config = Task {
//        bloopConfig(self, false)()
//      }
//    }
//
//    /**
//     * Setting to true enables skipping the bloop configuration generation
//     */
//    def skipBloop: Boolean = false
//  }
//
//  /**
//   * Extension class used to ensure that the config related tasks are
//   * cached alongside their respective modules, without requesting the user
//   * to extend a specific trait.
//   *
//   * This also ensures that we're not duplicating work between the global
//   * "install" task that traverse all modules in the build, and "local" tasks
//   * that traverse only their transitive dependencies.
//   */
//  private implicit class BloopOps(jm: JavaModule) extends MillModule {
//    override def moduleCtx = jm.moduleCtx
//
//    object bloop extends MillModule {
//      def writeConfigFile(isRootModule: Boolean): Command[(String, PathRef)] = Task.Command {
//        os.makeDir.all(bloopDir)
//        val path = bloopConfigPath(jm)
//        _root_.bloop.config.write(outer.bloopConfig(jm, isRootModule)(), path.toNIO)
//        Task.log.info(s"Wrote $path")
//        name(jm) -> PathRef(path)
//      }
//    }
//
//    def asBloop: Option[Module] = jm match {
//      case m: Module => Some(m)
//      case _ => None
//    }
//  }
//
//  /**
//   * All modules that are meant to be imported in Bloop
//   *
//   * @return sequence of modules and a boolean indicating whether the module is a root one
//   */
//  protected def computeModules: Seq[(JavaModule, Boolean)] =
//    evs()
//      .filter(_ != null)
//      .flatMap { eval =>
//        val rootModule = eval.rootModule
//        JavaModuleUtils.transitiveModules(rootModule, accept)
//          .collect { case jm: JavaModule => jm }
//          .map(mod => (mod, mod == rootModule))
//      }
//
//  // class-based pattern matching against path-dependant types doesn't seem to work.
//  private def accept(module: MillModule): Boolean =
//    if (module.isInstanceOf[JavaModule] && module.isInstanceOf[outer.Module])
//      !module.asInstanceOf[outer.Module].skipBloop
//    else true
//
//  /**
//   * Computes sources files paths for the whole project. Cached in a way
//   * that does not get invalidated upon source file change. Mainly called
//   * from module#sources in bloopInstall
//   */
//  def moduleSourceMap = Task {
//    val sources = Task.traverse(computeModules) {
//      case (m, _) =>
//        m.allSources.map { paths =>
//          name(m) -> paths.map(_.path)
//        }
//    }()
//    Result.Success(sources.toMap)
//  }
//
//  protected def name(m: JavaModule): String = m.bspDisplayName.replace('/', '-')
//
//  protected def bloopConfigPath(module: JavaModule): os.Path =
//    bloopDir / s"${name(module)}.json"
//
//  // ////////////////////////////////////////////////////////////////////////////
//  // Computation of the bloop configuration for a specific module
//  // ////////////////////////////////////////////////////////////////////////////
//
//  def bloopConfig(module: JavaModule, isRootModule: Boolean): Task[BloopConfig.File] = {
//    import _root_.bloop.config.Config
//    def out(m: JavaModule) = bloopDir / "out" / name(m)
//    def classes(m: JavaModule) = out(m) / "classes"
//
//    val javaConfig = Task.Anon {
//      val opts = module.javacOptions() ++ module.mandatoryJavacOptions()
//      Some(Config.Java(options = opts.toList))
//    }
//
//    // //////////////////////////////////////////////////////////////////////////
//    // Scalac
//    // //////////////////////////////////////////////////////////////////////////
//
//    val scalaConfig = module match {
//      case s: ScalaModule =>
//        Task.Anon {
//          Some(
//            BloopConfig.Scala(
//              organization = s.scalaOrganization(),
//              name = "scala-compiler",
//              version = s.scalaVersion(),
//              options = s.allScalacOptions().toList,
//              jars = s.scalaCompilerClasspath().map(_.path.toNIO).toList,
//              analysis = None,
//              setup = None
//            )
//          )
//        }
//      case _ => Task.Anon(None)
//    }
//
//    // //////////////////////////////////////////////////////////////////////////
//    // Platform (Jvm/Js/Native)
//    // //////////////////////////////////////////////////////////////////////////
//
//    def jsLinkerMode(m: JavaModule): Task[Config.LinkerMode] =
//      (m.asBloop match {
//        case Some(bm) => Task.Anon(bm.linkerMode())
//        case None => Task.Anon(None)
//      }).map(_.getOrElse(Config.LinkerMode.Debug))
//
//    // //////////////////////////////////////////////////////////////////////////
//    //  Classpath
//    // //////////////////////////////////////////////////////////////////////////
//
//    val classpath = Task.Anon {
//      val transitiveCompileClasspath = Task.traverse(module.transitiveModuleCompileModuleDeps)(m =>
//        Task.Anon { m.localCompileClasspath().map(_.path) ++ Seq(classes(m)) }
//      )().flatten
//
//      module.resolvedIvyDeps().map(_.path) ++
//        transitiveCompileClasspath ++
//        module.localCompileClasspath().map(_.path)
//    }
//
//    val isTestModule = module match {
//      case _: TestModule => true
//      case _ => false
//    }
//    val runtimeClasspathOpt =
//      if (isTestModule)
//        Task.Anon(None)
//      else
//        Task.Anon {
//          val cp = module.transitiveModuleDeps.map(classes) ++
//            module.resolvedRunIvyDeps().map(_.path) ++
//            module.unmanagedClasspath().map(_.path)
//          Some(cp.map(_.toNIO).toList)
//        }
//
//    val compileResources =
//      Task.Anon(module.compileResources().map(_.path.toNIO).toList)
//    val runtimeResources =
//      Task.Anon(compileResources() ++ module.resources().map(_.path.toNIO).toList)
//
//    val platform: Task[BloopConfig.Platform] = module match {
//      case m: ScalaJSModule =>
//        Task.Anon {
//          BloopConfig.Platform.Js(
//            BloopConfig.JsConfig.empty.copy(
//              version = m.scalaJSVersion(),
//              mode = jsLinkerMode(m)(),
//              kind = m.moduleKind() match {
//                case ModuleKind.NoModule => Config.ModuleKindJS.NoModule
//                case ModuleKind.CommonJSModule =>
//                  Config.ModuleKindJS.CommonJSModule
//                case ModuleKind.ESModule => Config.ModuleKindJS.ESModule
//              },
//              emitSourceMaps = m.jsEnvConfig() match {
//                case c: JsEnvConfig.NodeJs => c.sourceMap
//                case _ => false
//              },
//              jsdom = Some(false)
//            ),
//            mainClass = module.mainClass()
//          )
//        }
//      case m: ScalaNativeModule =>
//        Task.Anon {
//          BloopConfig.Platform.Native(
//            BloopConfig.NativeConfig.empty.copy(
//              version = m.scalaNativeVersion(),
//              mode = m.releaseMode() match {
//                case ReleaseMode.Debug => BloopConfig.LinkerMode.Debug
//                case ReleaseMode.ReleaseFast => BloopConfig.LinkerMode.Release
//                case ReleaseMode.ReleaseFull => BloopConfig.LinkerMode.Release
//                case ReleaseMode.ReleaseSize => BloopConfig.LinkerMode.Release
//              },
//              gc = m.nativeGC(),
//              targetTriple = m.nativeTarget(),
//              clang = m.nativeClang().path.toNIO,
//              clangpp = m.nativeClangPP().path.toNIO,
//              options = Config.NativeOptions(
//                m.nativeLinkingOptions().toList,
//                m.nativeCompileOptions().toList
//              ),
//              linkStubs = m.nativeLinkStubs()
//            ),
//            mainClass = module.mainClass()
//          )
//        }
//      case _ =>
//        Task.Anon {
//          BloopConfig.Platform.Jvm(
//            BloopConfig.JvmConfig(
//              home = Task.env.get("JAVA_HOME").map(s => os.Path(s).toNIO),
//              options = {
//                // See https://github.com/scalacenter/bloop/issues/1167
//                val forkArgs = module.forkArgs().toList
//                if (forkArgs.exists(_.startsWith("-Duser.dir="))) forkArgs
//                else s"-Duser.dir=$wd" :: forkArgs
//              }
//            ),
//            mainClass = module.mainClass(),
//            runtimeConfig = None,
//            classpath = runtimeClasspathOpt(),
//            resources = Some(runtimeResources())
//          )
//        }
//    }
//
//    // //////////////////////////////////////////////////////////////////////////
//    // Tests
//    // //////////////////////////////////////////////////////////////////////////
//
//    val testConfig = module match {
//      case m: TestModule =>
//        Task.Anon {
//          Some(
//            BloopConfig.Test(
//              frameworks = Seq(m.testFramework())
//                .map(f => Config.TestFramework(List(f)))
//                .toList,
//              options = Config.TestOptions(
//                excludes = List(),
//                arguments = List()
//              )
//            )
//          )
//        }
//      case _ => Task.Anon(None)
//    }
//
//    // //////////////////////////////////////////////////////////////////////////
//    //  Ivy dependencies + sources
//    // //////////////////////////////////////////////////////////////////////////
//
//    /**
//     * Resolves artifacts using coursier and creates the corresponding
//     * bloop config.
//     */
//    def artifacts(
//        repos: Seq[coursier.Repository],
//        deps: Seq[coursier.Dependency]
//    ): List[BloopConfig.Module] = {
//
//      import coursier._
//      import coursier.util._
//
//      import scala.concurrent.ExecutionContext.Implicits.global
//      Fetch(coursier.cache.FileCache())
//        .addRepositories(repos*)
//        .addDependencies(deps*)
//        .withMainArtifacts()
//        .addClassifiers(coursier.Classifier("sources"))
//        .runResult()
//        .fullDetailedArtifacts
//        .collect {
//          case (dep, _, _, Some(file)) if os.Path(file).ext == "jar" =>
//            (
//              dep.module.organization,
//              dep.module.name,
//              dep.version,
//              Option(dep.attributes.classifier).filter(_.nonEmpty),
//              file
//            )
//        }
//        .groupBy {
//          case (org, mod, version, _, _) => (org, mod, version)
//        }
//        .view
//        .mapValues {
//          _.map {
//            case (_, mod, _, classifier, file) =>
//              BloopConfig.Artifact(mod.value, classifier.map(_.value), None, file.toPath)
//          }.toList
//        }
//        .map {
//          case ((org, mod, version), artifacts) =>
//            BloopConfig.Module(
//              organization = org.value,
//              name = mod.value,
//              version = version,
//              configurations = None,
//              artifacts = artifacts
//            )
//        }
//        .toList
//    }
//
//    val millBuildDependencies: Task[List[BloopConfig.Module]] = Task.Anon {
//
//      val result = module.defaultResolver().artifacts(
//        BuildInfo.millAllDistDependencies
//          .split(',')
//          .filter(_.nonEmpty)
//          .map { str =>
//            str.split(":", 3) match {
//              case Array(org, name, ver) =>
//                val module =
//                  coursier.Module(coursier.Organization(org), coursier.ModuleName(name), Map.empty)
//                coursier.Dependency(module, ver)
//              case other =>
//                sys.error(
//                  s"Unexpected misshapen entry in BuildInfo.millEmbeddedDeps ('$str', expected 'org:name')"
//                )
//            }
//          },
//        sources = true
//      )
//
//      def moduleOf(dep: coursier.Dependency): BloopConfig.Module =
//        BloopConfig.Module(
//          dep.module.organization.value,
//          dep.module.name.value,
//          dep.version,
//          Some(dep.configuration.value).filter(_.nonEmpty),
//          Nil
//        )
//
//      val indices = result.fullDetailedArtifacts
//        .map {
//          case (dep, _, _, _) =>
//            moduleOf(dep)
//        }
//        .zipWithIndex
//        .reverseIterator
//        .toMap
//
//      result.fullDetailedArtifacts
//        .groupBy {
//          case (dep, _, _, _) =>
//            moduleOf(dep)
//        }
//        .toList
//        .sortBy {
//          case (mod, _) =>
//            indices(mod)
//        }
//        .map {
//          case (mod, artifacts) =>
//            mod.copy(
//              artifacts = artifacts.toList.collect {
//                case (_, pub, art, Some(file)) =>
//                  BloopConfig.Artifact(
//                    pub.name,
//                    Some(pub.classifier.value).filter(_.nonEmpty),
//                    None,
//                    file.toPath
//                  )
//              }
//            )
//        }
//    }
//
//    val bloopDependencies: Task[List[BloopConfig.Module]] = Task.Anon {
//      val repos = module.allRepositories()
//      // same as input of resolvedIvyDeps
//      val coursierDeps = Seq(
//        module.coursierDependency.withConfiguration(coursier.core.Configuration.provided),
//        module.coursierDependency
//      )
//      artifacts(repos, coursierDeps)
//    }
//
//    val addMillSources0 = addMillSources.getOrElse {
//      // We only try to resolve Mill's dependencies to get their source JARs
//      // if we're not running from sources. If Mill is running purely from its sources
//      // it's not is published in ~/.ivy2/local, so we can't get its source JARs.
//      !BloopImpl.isMillRunningFromSources
//    }
//    val allBloopDependencies: Task[List[BloopConfig.Module]] =
//      // Add Mill source JARs to root modules
//      if (isRootModule && addMillSources0)
//        Task.Anon {
//          bloopDependencies() ::: millBuildDependencies()
//        }
//      else
//        bloopDependencies
//
//    // //////////////////////////////////////////////////////////////////////////
//    //  Tying up
//    // //////////////////////////////////////////////////////////////////////////
//
//    val project = Task.Anon {
//      val mSources = moduleSourceMap()
//        .get(name(module))
//        .toSeq
//        .flatten
//        .map(_.toNIO)
//        .toList
//
//      val tags =
//        module match { case _: TestModule => List(BloopTag.Test); case _ => List(BloopTag.Library) }
//
//      BloopConfig.Project(
//        name = name(module),
//        directory = module.moduleDir.toNIO,
//        workspaceDir = Some(wd.toNIO),
//        sources = mSources,
//        sourcesGlobs = None,
//        sourceRoots = None,
//        dependencies =
//          (module.moduleDepsChecked ++ module.compileModuleDepsChecked).map(name).toList,
//        classpath = classpath().map(_.toNIO).toList,
//        out = out(module).toNIO,
//        classesDir = classes(module).toNIO,
//        resources = Some(compileResources()),
//        `scala` = scalaConfig(),
//        java = javaConfig(),
//        sbt = None,
//        test = testConfig(),
//        platform = Some(platform()),
//        resolution = Some(BloopConfig.Resolution(allBloopDependencies())),
//        tags = Some(tags),
//        sourceGenerators = None // TODO: are we supposed to hook generated sources here?
//      )
//    }
//
//    Task.Anon {
//      BloopConfig.File(
//        version = BloopConfig.File.LatestVersion,
//        project = project()
//      )
//    }
//  }
//
//  lazy val millDiscover = Discover[this.type]
//}
//
//object BloopImpl {
//  // If the class of BloopImpl is loaded from a directory rather than a JAR,
//  // then we're running from sources (the directory should be something
//  // like mill-repo/out/contrib/bloop/compile.dest/classes).
//  private lazy val isMillRunningFromSources =
//    Option(classOf[BloopImpl].getProtectionDomain.getCodeSource)
//      .flatMap(s => Option(s.getLocation))
//      .flatMap { url =>
//        try Some(Paths.get(url.toURI))
//        catch {
//          case _: FileSystemNotFoundException => None
//          case _: IllegalArgumentException => None
//        }
//      }
//      .exists(Files.isDirectory(_))
//}
