package mill.contrib.bloop

import ammonite.ops._
import bloop.config.Config.{File => BloopFile}
import bloop.config.ConfigEncoderDecoders._
import mill._
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
    Task.traverse(modules)(writeBloopConfig(bloopDir, _))
  }

  def modules = {
    if (ev != null) {
      val rootModule = ev.rootModule
      rootModule.millInternal.segmentsToModules.values.collect {
        case m: scalalib.JavaModule => m
      }.toSeq
    } else Seq()
  }

  // Precomputing sources to avoid cache invalidation resulting
  // from calling module#sources in bloopInstall
  def moduleSourceMap: Target[Map[String, Seq[Path]]] = T {
    Task
      .traverse(modules)(_.allSources)
      .map(
        modules
          .map(j => j.millModuleSegments.toString)
          .zip(_)
          .toMap
          .mapValues(_.map(_.path)))
  }

  def bloopConfig(bloopDir: Path,
                  module: JavaModule): Task[(BloopFile, Path)] = {
    import _root_.bloop.config.Config
    def name(m: JavaModule) = m.millModuleSegments.render
    def out(m: JavaModule) = bloopDir / "out" / m.millModuleSegments.render
    def classes(m: JavaModule) = out(m) / "classes"

    val javaConfig =
      module.javacOptions.map(opts => Some(Config.Java(options = opts.toList)))

    val scalaConfig = module match {
      case s: ScalaModule =>
        T.task {
          val pluginOptions = s.scalacPluginClasspath().map { pathRef =>
            s"-Xplugin:${pathRef.path}"
          }

          Some(
            Config.Scala(
              organization = "org.scala-lang",
              name = "scala-compiler",
              version = s.scalaVersion(),
              options = (s.scalacOptions() ++ pluginOptions).toList,
              jars = s.scalaCompilerClasspath().map(_.path.toNIO).toList,
              analysis = None,
              setup = None
            )
          )
        }
      case _ => T.task(None)
    }

    val platform = T.task {
      Config.Platform.Jvm(
        Config.JvmConfig(
          home = T.ctx().env.get("JAVA_HOME").map(s => Path(s).toNIO),
          options = module.forkArgs().toList
        ),
        mainClass = module.mainClass()
      )
    }

    val testConfig = module match {
      case m: TestModule =>
        T.task {
          Some(
            Config.Test(
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
        .get(module.millModuleSegments.toString)
        .toSeq
        .flatten
        .map(_.toNIO)
        .toList

      Config.Project(
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
        resolution = None
      )
    }

    T.task {
      val filePath = bloopDir / s"${name(module)}.json"
      val file = Config.File(
        version = Config.File.LatestVersion,
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
