package mill.contrib.bloop

import ammonite.ops._
import bloop.config.ConfigEncoderDecoders._
import bloop.config.{Config => BloopConfig}
import mill._
import mill.api.Loose
import mill.define.{Module => MillModule, _}
import mill.eval.Evaluator
import mill.scalalib._
import os.pwd

/**
  * Implementation of the Bloop related tasks. Inherited by the
  * `mill.contrib.Bloop` object, and usable in tests by passing
  * a custom evaluator.
  */
class BloopImpl(ev: () => Evaluator, wd: Path) extends ExternalModule { outer =>

  /**
    * Generates bloop configuration files reflecting the build,
    * under pwd/.bloop.
    */
  def install() = T.command {
    Task.traverse(computeModules)(_.bloop.writeConfig)
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
  }

  private val bloopDir = wd / ".bloop"

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
  def moduleSourceMap: Target[Map[String, Seq[Path]]] = T {
    val sources = Task.traverse(computeModules) { m =>
      m.allSources.map { paths =>
        m.millModuleSegments.render -> paths.map(_.path)
      }
    }()
    sources.toMap
  }

  protected def name(m: JavaModule) = m.millModuleSegments.render

  protected def bloopConfigPath(module: JavaModule): Path =
    bloopDir / s"${name(module)}.json"

  //////////////////////////////////////////////////////////////////////////////
  // SemanticDB related configuration
  //////////////////////////////////////////////////////////////////////////////

  // Version of the semanticDB plugin.
  def semanticDBVersion: String = "4.1.4"

  // Scala versions supported by semantic db. Needs to be updated when
  // bumping semanticDBVersion.
  // See [https://github.com/scalameta/metals/blob/333ab6fc00fb3542bcabd0dac51b91b72798768a/build.sbt#L121]
  def semanticDBSupported = Set(
    "2.12.8",
    "2.12.7",
    "2.12.6",
    "2.12.5",
    "2.12.4",
    "2.11.12",
    "2.11.11",
    "2.11.10",
    "2.11.9"
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

    val platform = T.task {
      BloopConfig.Platform.Jvm(
        BloopConfig.JvmConfig(
          home = T.ctx().env.get("JAVA_HOME").map(s => Path(s).toNIO),
          options = module.forkArgs().toList
        ),
        mainClass = module.mainClass()
      )
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
          .map(d =>
            d.copy(attributes = d.attributes.copy(classifier = coursier.Classifier("sources")))
          )
          .toSeq
      )

      import scala.concurrent.ExecutionContext.Implicits.global
      val unresolved = Resolution(deps)
      val fetch = ResolutionProcess.fetch(repos, coursier.cache.Cache.default.fetch)
      val gatherTask = for {
        resolved <- unresolved.process.run(fetch)
        resolvedSources <- source(resolved).process.run(fetch)
        all = resolved.dependencyArtifacts ++ resolvedSources.dependencyArtifacts
        gathered <- Gather[Task].gather(all.distinct.map {
          case (dep, art) => coursier.cache.Cache.default.file(art).run.map(dep -> _)
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
          module.compileIvyDeps() ++ module.transitiveIvyDeps() ++ scalaLibIvyDeps()
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
