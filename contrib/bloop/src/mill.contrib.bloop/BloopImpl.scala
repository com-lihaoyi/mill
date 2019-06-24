package mill.contrib.bloop

import ammonite.ops._
import bloop.config.ConfigEncoderDecoders._
import bloop.config.{Config => BloopConfig}
import mill._
import mill.api.Loose
import mill.define.{Module => MillModule, _}
import mill.eval.Evaluator
import mill.scalajslib.ScalaJSModule
import mill.scalajslib.api.{JsEnvConfig, ModuleKind}
import mill.scalalib._
import mill.scalanativelib.ScalaNativeModule
import mill.scalanativelib.api.ReleaseMode
import os.pwd

/**
  * Implementation of the Bloop related tasks. Inherited by the
  * `mill.contrib.Bloop` object, and usable in tests by passing
  * a custom evaluator.
  */
class BloopImpl(ev: () => Evaluator, wd: Path) extends ExternalModule { outer =>

  private val bloopDir = wd / ".bloop"

  /**
    * Generates bloop configuration files reflecting the build,
    * under pwd/.bloop.
    */
  def install() = T.command {
    val res = Task.traverse(computeModules)(_.bloop.writeConfig)()
    val written = res.map(_._2).map(_.path)
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
  trait Module extends MillModule with CirceCompat { self: JavaModule =>

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
  private implicit class BloopOps(jm: JavaModule)
      extends MillModule
      with CirceCompat {
    override def millOuterCtx = jm.millOuterCtx

    object bloop extends MillModule {
      def config = T { outer.bloopConfig(jm) }

      def writeConfig: Target[(String, PathRef)] = T {
        mkdir(bloopDir)
        val path = bloopConfigPath(jm)
        _root_.bloop.config.write(config(), path.toNIO)
        T.ctx().log.info(s"Wrote $path")
        name(jm) -> PathRef(path)
      }

      def writeTransitiveConfig = T {
        Task.traverse(jm.transitiveModuleDeps)(_.bloop.writeConfig)
      }
    }

    def asBloop: Option[Module] = jm match {
      case m: Module => Some(m)
      case _         => None
    }
  }

  private def computeModules: Seq[JavaModule] = {
    val eval = ev()
    if (eval != null) {
      val rootModule = eval.rootModule
      rootModule.millInternal.segmentsToModules.values.collect {
        case m: scalalib.JavaModule => m
      }.toSeq
    } else Seq()
  }

  /**
    * Computes sources files paths for the whole project. Cached in a way
    * that does not get invalidated upon sourcefile change. Mainly called
    * from module#sources in bloopInstall
    */
  def moduleSourceMap = T.input {
    val sources = Task.traverse(computeModules) { m =>
      m.allSources.map { paths =>
        m.millModuleSegments.render -> paths.map(_.path)
      }
    }()
    mill.eval.Result.Success(sources.toMap)
  }

  protected def name(m: JavaModule) = m.millModuleSegments.render

  protected def bloopConfigPath(module: JavaModule): Path =
    bloopDir / s"${name(module)}.json"

  //////////////////////////////////////////////////////////////////////////////
  // SemanticDB related configuration
  //////////////////////////////////////////////////////////////////////////////

  // Version of the semanticDB plugin.
  def semanticDBVersion: String = "4.1.12"

  // Scala versions supported by semantic db. Needs to be updated when
  // bumping semanticDBVersion.
  // See [https://github.com/scalameta/metals/blob/333ab6fc00fb3542bcabd0dac51b91b72798768a/build.sbt#L121]
  def semanticDBSupported = Set(
    "2.13.0",
    "2.12.8",
    "2.12.7",
    "2.11.12"
  )

  // Recommended for metals usage.
  def semanticDBOptions = List(
    s"-P:semanticdb:sourceroot:$pwd",
    "-P:semanticdb:synthetics:on",
    "-P:semanticdb:failures:warning"
  )

  //////////////////////////////////////////////////////////////////////////////
  // Computation of the bloop configuration for a specific module
  //////////////////////////////////////////////////////////////////////////////

  def bloopConfig(module: JavaModule): Task[BloopConfig.File] = {
    import _root_.bloop.config.Config
    def out(m: JavaModule) = bloopDir / "out" / m.millModuleSegments.render
    def classes(m: JavaModule) = out(m) / "classes"

    val javaConfig =
      module.javacOptions.map(opts => Some(Config.Java(options = opts.toList)))

    ////////////////////////////////////////////////////////////////////////////
    // Scalac
    ////////////////////////////////////////////////////////////////////////////

    val scalaConfig = module match {
      case s: ScalaModule =>
        val semanticDb = s.resolveDeps(s.scalaVersion.map {
          case scalaV if semanticDBSupported(scalaV) =>
            Agg(ivy"org.scalameta:semanticdb-scalac_$scalaV:$semanticDBVersion")
          case _ => Agg()
        })

        T.task {
          val pluginCp = semanticDb() ++ s.scalacPluginClasspath()
          val pluginOptions = pluginCp.map { pathRef =>
            s"-Xplugin:${pathRef.path}"
          }

          val allScalacOptions =
            (s.scalacOptions() ++ pluginOptions ++ semanticDBOptions).toList
          Some(
            BloopConfig.Scala(
              organization = "org.scala-lang",
              name = "scala-compiler",
              version = s.scalaVersion(),
              options = allScalacOptions,
              jars = s.scalaCompilerClasspath().map(_.path.toNIO).toList,
              analysis = None,
              setup = None
            )
          )
        }
      case _ => T.task(None)
    }

    ////////////////////////////////////////////////////////////////////////////
    // Platform (Jvm/Js/Native)
    ////////////////////////////////////////////////////////////////////////////

    def jsLinkerMode(m: JavaModule): Task[Config.LinkerMode] =
      (m.asBloop match {
        case Some(bm) => T.task(bm.linkerMode())
        case None     => T.task(None)
      }).map(_.getOrElse(Config.LinkerMode.Debug))

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
              },
              emitSourceMaps = m.jsEnvConfig() match{
                case c: JsEnvConfig.NodeJs => c.sourceMap
                case _ => false
              },
              jsdom = Some(false),
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
                case ReleaseMode.Release => BloopConfig.LinkerMode.Release
              },
              gc = m.nativeGC(),
              targetTriple = m.nativeTarget(),
              nativelib = m.nativeLibJar().path.toNIO,
              clang = m.nativeClang().toNIO,
              clangpp = m.nativeClangPP().toNIO,
              options = Config.NativeOptions(
                m.nativeLinkingOptions().toList,
                m.nativeCompileOptions().toList
              ),
              linkStubs = m.nativeLinkStubs(),
            ),
            mainClass = module.mainClass()
          )
        }
      case _ =>
        T.task {
          BloopConfig.Platform.Jvm(
            BloopConfig.JvmConfig(
              home = T.ctx().env.get("JAVA_HOME").map(s => Path(s).toNIO),
              options = module.forkArgs().toList
            ),
            mainClass = module.mainClass()
          )
        }
    }

    ////////////////////////////////////////////////////////////////////////////
    // Tests
    ////////////////////////////////////////////////////////////////////////////

    val testConfig = module match {
      case m: TestModule =>
        T.task {
          Some(
            BloopConfig.Test(
              frameworks = m
                .testFrameworks()
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

    ////////////////////////////////////////////////////////////////////////////
    //  Ivy dependencies + sources
    ////////////////////////////////////////////////////////////////////////////

    val scalaLibraryIvyDeps = module match {
      case x: ScalaModule => x.scalaLibraryIvyDeps
      case _              => T.task { Loose.Agg.empty[Dep] }
    }

    /**
      * Resolves artifacts using coursier and creates the corresponding
      * bloop config.
      */
    def artifacts(repos: Seq[coursier.Repository],
                  deps: Seq[coursier.Dependency]): List[BloopConfig.Module] = {

      import coursier._
      import coursier.util._

      def source(r: Resolution) = Resolution(
        r.dependencies
          .map(
            d =>
              d.copy(attributes =
                d.attributes.copy(classifier = coursier.Classifier("sources"))))
          .toSeq
      )

      import scala.concurrent.ExecutionContext.Implicits.global
      val unresolved = Resolution(deps)
      val fetch =
        ResolutionProcess.fetch(repos, coursier.cache.Cache.default.fetch)
      val gatherTask = for {
        resolved <- unresolved.process.run(fetch)
        resolvedSources <- source(resolved).process.run(fetch)
        all = resolved.dependencyArtifacts ++ resolvedSources.dependencyArtifacts
        gathered <- Gather[Task].gather(all.distinct.map {
          case (dep, art) =>
            coursier.cache.Cache.default.file(art).run.map(dep -> _)
        })
      } yield
        gathered
          .collect {
            case (dep, Right(file)) if Path(file).ext == "jar" =>
              (dep.module.organization,
               dep.module.name,
               dep.version,
               Option(dep.attributes.classifier).filter(_.nonEmpty),
               file)
          }
          .groupBy {
            case (org, mod, version, _, _) => (org, mod, version)
          }
          .mapValues {
            _.map {
              case (_, mod, _, classifier, file) =>
                BloopConfig.Artifact(mod.value,
                                     classifier.map(_.value),
                                     None,
                                     file.toPath)
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
      val repos = module.repositories
      val allIvyDeps = module
        .transitiveIvyDeps() ++ scalaLibraryIvyDeps() ++ module.compileIvyDeps()
      val coursierDeps =
        allIvyDeps.map(module.resolveCoursierDependency()).toList
      BloopConfig.Resolution(artifacts(repos, coursierDeps))
    }

    ////////////////////////////////////////////////////////////////////////////
    //  Classpath
    ////////////////////////////////////////////////////////////////////////////

    val scalaLibIvyDeps = module match {
      case s: ScalaModule => s.scalaLibraryIvyDeps
      case _              => T.task(Loose.Agg.empty[Dep])
    }

    val ivyDepsClasspath =
      module
        .resolveDeps(T.task {
          module.compileIvyDeps() ++ module
            .transitiveIvyDeps() ++ scalaLibIvyDeps()
        })
        .map(_.map(_.path).toSeq)

    def transitiveClasspath(m: JavaModule): Task[Seq[Path]] = T.task {
      m.moduleDeps.map(classes) ++
        m.unmanagedClasspath().map(_.path) ++
        Task.traverse(m.moduleDeps)(transitiveClasspath)().flatten
    }

    val classpath = T
      .task(transitiveClasspath(module)() ++ ivyDepsClasspath())
      .map(_.distinct)
    val resources = T.task(module.resources().map(_.path.toNIO).toList)

    ////////////////////////////////////////////////////////////////////////////
    //  Tying up
    ////////////////////////////////////////////////////////////////////////////

    val project = T.task {
      val mSources = moduleSourceMap()
        .get(name(module))
        .toSeq
        .flatten
        .map(_.toNIO)
        .toList

      BloopConfig.Project(
        name = name(module),
        directory = module.millSourcePath.toNIO,
        sources = mSources,
        dependencies = module.moduleDeps.map(name).toList,
        classpath = classpath().map(_.toNIO).toList,
        out = out(module).toNIO,
        classesDir = classes(module).toNIO,
        resources = Some(resources()),
        `scala` = scalaConfig(),
        java = javaConfig(),
        sbt = None,
        test = testConfig(),
        platform = Some(platform()),
        resolution = Some(bloopResolution())
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
