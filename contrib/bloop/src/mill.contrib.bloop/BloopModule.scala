package mill.contrib.bloop

import ammonite.ops._
import bloop.config.ConfigEncoderDecoders._
import bloop.config.{Config => BloopConfig}
import mill._
import mill.api.Loose
import mill.define._
import mill.eval.Evaluator
import mill.scalalib._

trait BloopModule extends Module with CirceCompat { self: JavaModule =>

  object bloop extends Module {

    def config = T {
      // Forcing the generation of the config for all modules here
      val _ = BloopModule.install() // NB: looks like it has to be assigned to be executed
      BloopModule.bloopConfig(pwd / ".bloop", self)()._1
    }

  }
}

object BloopModule
    extends BloopModuleImpl(Evaluator.currentEvaluator.get(), pwd)

class BloopModuleImpl(ev: Evaluator, wd: Path) extends ExternalModule {

  def install = T {
    val bloopDir = wd / ".bloop"
    mkdir(bloopDir)
    Task.traverse(computeModules)(writeBloopConfig(bloopDir, _))
  }

  def computeModules = {
    // This is necessary as ev will be null when running install
    // from the global module otherwise
    val eval = Option(ev).getOrElse(Evaluator.currentEvaluator.get())
    if (eval != null) {
      val rootModule = eval.rootModule
      rootModule.millInternal.segmentsToModules.values.collect {
        case m: scalalib.JavaModule => m
      }.toSeq
    } else Seq()
  }

  /**
    * Computes sources files paths for the whole project. Cached in a way
    * that doesnot get invalidated upon sourcefile change. Mainly called
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

  /**
    * Computes the bloop configuration for a mill module.
    */
  def bloopConfig(bloopDir: Path,
                  module: JavaModule): Task[(BloopConfig.File, Path)] = {
    import _root_.bloop.config.Config
    def name(m: JavaModule) = m.millModuleSegments.render
    def out(m: JavaModule) = bloopDir / "out" / m.millModuleSegments.render
    def classes(m: JavaModule) = out(m) / "classes"

    val javaConfig =
      module.javacOptions.map(opts => Some(Config.Java(options = opts.toList)))

    ////////////////////////////////////////////////////////////////////////////
    // Scalac
    ////////////////////////////////////////////////////////////////////////////

    val scalaConfig = module match {
      case s: ScalaModule =>
        T.task {
          val pluginOptions = s.scalacPluginClasspath().map { pathRef =>
            s"-Xplugin:${pathRef.path}"
          }

          // Recommended for metals usage.
          val semanticDbOptions = List(
            s"-P:semanticdb:sourceroot:$pwd",
            "-P:semanticdb:synthetics:on",
            "-P:semanticdb:failures:warning"
          )
          val allScalacOptions =
            (s.scalacOptions() ++ pluginOptions ++ semanticDbOptions).toList

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
        r.dependencies.map(d =>
          d.copy(attributes = d.attributes.copy(classifier = "sources")))
      )

      import scala.concurrent.ExecutionContext.Implicits.global
      val unresolved = Resolution(deps.toSet)
      val fetch = Fetch.from(repos, Cache.fetch[Task]())
      val gatherTask = for {
        resolved <- unresolved.process.run(fetch)
        resolvedSources <- source(resolved).process.run(fetch)
        all = resolved.dependencyArtifacts ++ resolvedSources.dependencyArtifacts
        gathered <- Gather[Task].gather(all.distinct.map {
          case (dep, art) => Cache.file[Task](art).run.map(dep -> _)
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
                BloopConfig.Artifact(mod, classifier, None, file.toPath)
            }.toList
          }
          .map {
            case ((org, mod, version), artifacts) =>
              BloopConfig.Module(
                organization = org,
                name = mod,
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

    val ivyDepsClasspath =
      module
        .resolveDeps(T.task {
          module.compileIvyDeps() ++ module.transitiveIvyDeps()
        })
        .map(_.map(_.path).toSeq)

    def transitiveClasspath(m: JavaModule): Task[Seq[Path]] = T.task {
      m.moduleDeps.map(classes) ++
        m.unmanagedClasspath().map(_.path) ++
        Task.traverse(m.moduleDeps)(transitiveClasspath)().flatten
    }

    val classpath = T.task(transitiveClasspath(module)() ++ ivyDepsClasspath())
    val resources = T.task(module.resources().map(_.path.toNIO).toList)

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
      val filePath = bloopDir / s"${name(module)}.json"
      val file = BloopConfig.File(
        version = BloopConfig.File.LatestVersion,
        project = project()
      )
      file -> filePath
    }
  }

  def writeBloopConfig(bloopDir: Path, module: JavaModule) = T.task {
    val (config, path) = bloopConfig(bloopDir, module)()
    bloop.config.write(config, path.toNIO)
    path
  }

  lazy val millDiscover = Discover[this.type]
}
