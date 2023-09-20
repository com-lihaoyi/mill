package mill.contrib.bloop

import _root_.bloop.config.{Config => BloopConfig, Tag => BloopTag}
import mill._
import mill.api.Result
import mill.define.{Discover, ExternalModule, Module => MillModule}
import mill.eval.Evaluator
import mill.scalalib.internal.{JavaModuleUtils, ModuleUtils}
import mill.scalajslib.ScalaJSModule
import mill.scalajslib.api.{JsEnvConfig, ModuleKind}
import mill.scalalib._
import mill.scalanativelib.ScalaNativeModule
import mill.scalanativelib.api.ReleaseMode

/**
 * Implementation of the Bloop related tasks. Inherited by the
 * `mill.contrib.bloop.Bloop` object, and usable in tests by passing
 * a custom evaluator.
 */
class BloopImpl(ev: () => Evaluator, wd: os.Path) extends ExternalModule { outer =>
  import BloopFormats._

  private val bloopDir = wd / ".bloop"

  /**
   * Generates bloop configuration files reflecting the build,
   * under pwd/.bloop.
   */
  def install() = T.command {
    val res = T.traverse(computeModules)(_.bloop.writeConfig)()
    val written = res.map(_._2).map(_.path)
    // Make bloopDir if it doesn't exists
    if (!os.exists(bloopDir)) {
      os.makeDir(bloopDir)
    }
    // Cleaning up configs that weren't generated in this run.
    os.list(bloopDir)
      .filter(_.ext == "json")
      .filterNot(written.contains)
      .foreach(os.remove)
    res
  }

  /**
   * Trait that can be mixed-in to quickly access the bloop config
   * of the module.
   *
   * {{{
   * object myModule extends ScalaModule with Bloop.Module {
   *    ...
   * }
   * }}}
   */
  trait Module extends MillModule { self: JavaModule =>

    /**
     * Allows to tell Bloop whether it should use "fullOptJs" or
     * "fastOptJs" when compiling. Used exclusively with ScalaJsModules.
     */
    def linkerMode: T[Option[BloopConfig.LinkerMode]] = None

    object bloop extends MillModule {
      def config = T {
        new BloopOps(self).bloop.config()
      }
    }

    /**
     * Setting to true enables skipping the bloop configuration generation
     */
    def skipBloop: Boolean = false
  }

  /**
   * Extension class used to ensure that the config related tasks are
   * cached alongside their respective modules, without requesting the user
   * to extend a specific trait.
   *
   * This also ensures that we're not duplicating work between the global
   * "install" task that traverse all modules in the build, and "local" tasks
   * that traverse only their transitive dependencies.
   */
  private implicit class BloopOps(jm: JavaModule) extends MillModule {
    override def millOuterCtx = jm.millOuterCtx

    object bloop extends MillModule {
      def config = T { outer.bloopConfig(jm) }

      def writeConfigFile(): Command[(String, PathRef)] = T.command {
        os.makeDir.all(bloopDir)
        val path = bloopConfigPath(jm)
        _root_.bloop.config.write(config(), path.toNIO)
        T.log.info(s"Wrote $path")
        name(jm) -> PathRef(path)
      }

      @deprecated("Use writeConfigFile instead.", "Mill after 0.10.9")
      def writeConfig: Target[(String, PathRef)] = T {
        writeConfigFile()()
      }
    }

    def asBloop: Option[Module] = jm match {
      case m: Module => Some(m)
      case _ => None
    }
  }

  // Compute all transitive modules from build children and via moduleDeps
  @deprecated("Use mill.internal.JavaModuleUtils.transitiveModules instead", since = "mill 0.10.3")
  def transitiveModules(
      mod: define.Module,
      found: Seq[define.Module] = Seq.empty
  ): Seq[define.Module] = {
    JavaModuleUtils.transitiveModules(mod, accept)
  }

  protected def computeModules: Seq[JavaModule] = {
    val eval = ev()
    if (eval != null)
      JavaModuleUtils.transitiveModules(eval.rootModule, accept)
        .collect { case jm: JavaModule => jm }
    else
      Seq.empty
  }

  // class-based pattern matching against path-dependant types doesn't seem to work.
  private def accept(module: MillModule): Boolean =
    if (module.isInstanceOf[JavaModule] && module.isInstanceOf[outer.Module])
      !module.asInstanceOf[outer.Module].skipBloop
    else true

  /**
   * Computes sources files paths for the whole project. Cached in a way
   * that does not get invalidated upon sourcefile change. Mainly called
   * from module#sources in bloopInstall
   */
  def moduleSourceMap = T.input {
    val sources = T.traverse(computeModules) { m =>
      m.allSources.map { paths =>
        name(m) -> paths.map(_.path)
      }
    }()
    Result.Success(sources.toMap)
  }

  protected def name(m: JavaModule) = ModuleUtils.moduleDisplayName(m) match {
    case "" => "root-module"
    case n => n
  }

  protected def bloopConfigPath(module: JavaModule): os.Path =
    bloopDir / s"${name(module)}.json"

  // ////////////////////////////////////////////////////////////////////////////
  // Computation of the bloop configuration for a specific module
  // ////////////////////////////////////////////////////////////////////////////

  def bloopConfig(module: JavaModule): Task[BloopConfig.File] = {
    import _root_.bloop.config.Config
    def out(m: JavaModule) = bloopDir / "out" / name(m)
    def classes(m: JavaModule) = out(m) / "classes"

    val javaConfig =
      module.javacOptions.map(opts => Some(Config.Java(options = opts.toList)))

    // //////////////////////////////////////////////////////////////////////////
    // Scalac
    // //////////////////////////////////////////////////////////////////////////

    val scalaConfig = module match {
      case s: ScalaModule =>
        T.task {
          Some(
            BloopConfig.Scala(
              organization = s.scalaOrganization(),
              name = "scala-compiler",
              version = s.scalaVersion(),
              options = s.allScalacOptions().toList,
              jars = s.scalaCompilerClasspath().map(_.path.toNIO).toList,
              analysis = None,
              setup = None
            )
          )
        }
      case _ => T.task(None)
    }

    // //////////////////////////////////////////////////////////////////////////
    // Platform (Jvm/Js/Native)
    // //////////////////////////////////////////////////////////////////////////

    def jsLinkerMode(m: JavaModule): Task[Config.LinkerMode] =
      (m.asBloop match {
        case Some(bm) => T.task(bm.linkerMode())
        case None => T.task(None)
      }).map(_.getOrElse(Config.LinkerMode.Debug))

    // //////////////////////////////////////////////////////////////////////////
    //  Classpath
    // //////////////////////////////////////////////////////////////////////////

    val classpath = T.task {
      val depModules = (module.compileModuleDeps ++ module.recursiveModuleDeps).distinct
      // dep modules ++ ivy deps ++ unmanaged
      depModules.map(classes) ++
        module.resolvedIvyDeps().map(_.path) ++
        module.unmanagedClasspath().map(_.path)
    }

    val runtimeClasspath = T.task {
      // dep modules ++ ivy deps ++ unmanaged
      module.recursiveModuleDeps.map(classes) ++
        module.resolvedRunIvyDeps().map(_.path) ++
        module.unmanagedClasspath().map(_.path)
    }

    val resources = T.task(module.resources().map(_.path.toNIO).toList)

    val platform: Task[BloopConfig.Platform] = module match {
      case m: ScalaJSModule =>
        T.task {
          BloopConfig.Platform.Js(
            BloopConfig.JsConfig.empty.copy(
              version = m.scalaJSVersion(),
              mode = jsLinkerMode(m)(),
              kind = m.moduleKind() match {
                case ModuleKind.NoModule => Config.ModuleKindJS.NoModule
                case ModuleKind.CommonJSModule =>
                  Config.ModuleKindJS.CommonJSModule
                case ModuleKind.ESModule => Config.ModuleKindJS.ESModule
              },
              emitSourceMaps = m.jsEnvConfig() match {
                case c: JsEnvConfig.NodeJs => c.sourceMap
                case _ => false
              },
              jsdom = Some(false)
            ),
            mainClass = module.mainClass()
          )
        }
      case m: ScalaNativeModule =>
        T.task {
          BloopConfig.Platform.Native(
            BloopConfig.NativeConfig.empty.copy(
              version = m.scalaNativeVersion(),
              mode = m.releaseMode() match {
                case ReleaseMode.Debug => BloopConfig.LinkerMode.Debug
                case ReleaseMode.ReleaseFast => BloopConfig.LinkerMode.Release
                case ReleaseMode.ReleaseFull => BloopConfig.LinkerMode.Release
                case ReleaseMode.ReleaseSize => BloopConfig.LinkerMode.Release
              },
              gc = m.nativeGC(),
              targetTriple = m.nativeTarget(),
              clang = m.nativeClang().toNIO,
              clangpp = m.nativeClangPP().toNIO,
              options = Config.NativeOptions(
                m.nativeLinkingOptions().toList,
                m.nativeCompileOptions().toList
              ),
              linkStubs = m.nativeLinkStubs()
            ),
            mainClass = module.mainClass()
          )
        }
      case _ =>
        T.task {
          BloopConfig.Platform.Jvm(
            BloopConfig.JvmConfig(
              home = T.env.get("JAVA_HOME").map(s => os.Path(s).toNIO),
              options = {
                // See https://github.com/scalacenter/bloop/issues/1167
                val forkArgs = module.forkArgs().toList
                if (forkArgs.exists(_.startsWith("-Duser.dir="))) forkArgs
                else s"-Duser.dir=$wd" :: forkArgs
              }
            ),
            mainClass = module.mainClass(),
            runtimeConfig = None,
            classpath = Some(runtimeClasspath().map(_.toNIO).toList),
            resources = Some(module.resources().map(_.path.toNIO).toList)
          )
        }
    }

    // //////////////////////////////////////////////////////////////////////////
    // Tests
    // //////////////////////////////////////////////////////////////////////////

    val testConfig = module match {
      case m: TestModule =>
        T.task {
          Some(
            BloopConfig.Test(
              frameworks = Seq(m.testFramework())
                .map(f => Config.TestFramework(List(f)))
                .toList,
              options = Config.TestOptions(
                excludes = List(),
                arguments = List()
              )
            )
          )
        }
      case _ => T.task(None)
    }

    // //////////////////////////////////////////////////////////////////////////
    //  Ivy dependencies + sources
    // //////////////////////////////////////////////////////////////////////////

    /**
     * Resolves artifacts using coursier and creates the corresponding
     * bloop config.
     */
    def artifacts(
        repos: Seq[coursier.Repository],
        deps: Seq[coursier.Dependency]
    ): List[BloopConfig.Module] = {

      import coursier._
      import coursier.util._

      def source(r: Resolution) = Resolution(
        r.dependencies
          .map(d =>
            d.withAttributes(
              d.attributes.withClassifier(coursier.Classifier("sources"))
            )
          )
          .toSeq
      )

      import scala.concurrent.ExecutionContext.Implicits.global
      val unresolved = Resolution(deps)
      val fetch =
        ResolutionProcess.fetch(repos, coursier.cache.Cache.default.fetch)
      val gatherTask =
        for {
          resolved <- unresolved.process.run(fetch)
          resolvedSources <- source(resolved).process.run(fetch)
          all = resolved.dependencyArtifacts() ++ resolvedSources.dependencyArtifacts()
          gathered <- Gather[Task].gather(all.distinct.map {
            case (dep, pub, art) =>
              coursier.cache.Cache.default.file(art).run.map(dep -> _)
          })
        } yield gathered
          .collect {
            case (dep, Right(file)) if os.Path(file).ext == "jar" =>
              (
                dep.module.organization,
                dep.module.name,
                dep.version,
                Option(dep.attributes.classifier).filter(_.nonEmpty),
                file
              )
          }
          .groupBy {
            case (org, mod, version, _, _) => (org, mod, version)
          }
          .mapValues {
            _.map {
              case (_, mod, _, classifier, file) =>
                BloopConfig.Artifact(mod.value, classifier.map(_.value), None, file.toPath)
            }.toList
          }
          .map {
            case ((org, mod, version), artifacts) =>
              BloopConfig.Module(
                organization = org.value,
                name = mod.value,
                version = version,
                configurations = None,
                artifacts = artifacts
              )
          }

      gatherTask.unsafeRun().toList
    }

    val bloopResolution: Task[BloopConfig.Resolution] = T.task {
      val repos = module.repositoriesTask()
      // same as input of resolvedIvyDeps
      val allIvyDeps = module.transitiveIvyDeps() ++ module.transitiveCompileIvyDeps()
      val coursierDeps = allIvyDeps.map(_.dep).toList
      BloopConfig.Resolution(artifacts(repos, coursierDeps))
    }

    // //////////////////////////////////////////////////////////////////////////
    //  Tying up
    // //////////////////////////////////////////////////////////////////////////

    val project = T.task {
      val mSources = moduleSourceMap()
        .get(name(module))
        .toSeq
        .flatten
        .map(_.toNIO)
        .toList

      val tags =
        module match { case _: TestModule => List(BloopTag.Test); case _ => List(BloopTag.Library) }

      BloopConfig.Project(
        name = name(module),
        directory = module.millSourcePath.toNIO,
        workspaceDir = Some(wd.toNIO),
        sources = mSources,
        sourcesGlobs = None,
        sourceRoots = None,
        dependencies = (module.moduleDeps ++ module.compileModuleDeps).map(name).toList,
        classpath = classpath().map(_.toNIO).toList,
        out = out(module).toNIO,
        classesDir = classes(module).toNIO,
        resources = Some(resources()),
        `scala` = scalaConfig(),
        java = javaConfig(),
        sbt = None,
        test = testConfig(),
        platform = Some(platform()),
        resolution = Some(bloopResolution()),
        tags = Some(tags),
        sourceGenerators = None // TODO: are we supposed to hook generated sources here?
      )
    }

    T.task {
      BloopConfig.File(
        version = BloopConfig.File.LatestVersion,
        project = project()
      )
    }
  }

  lazy val millDiscover = Discover[this.type]
}
