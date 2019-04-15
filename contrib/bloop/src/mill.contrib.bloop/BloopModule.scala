package mill.contrib.bloop

import ammonite.ops._
import bloop.config.Config.{File => BloopFile}
import bloop.config.ConfigEncoderDecoders._
import io.circe.{Decoder, Encoder, Json}
import mill._
import mill.define._
import mill.eval.Evaluator
import mill.modules.Jvm
import mill.scalalib._
import mill.scalalib.api.CompilationResult
import upickle.core.Visitor
import upickle.default

trait BloopModule extends Module { self: JavaModule =>

  object bloop extends Module {

    def compile = T.input {
      val prj = config().project
      val cmd = Seq("bloop", "compile", prj.name)
      val classes = Path(prj.classesDir)
      val out = Path(prj.out)
      val analysisFile = out / s"${prj.name}-analysis.bin"
      Jvm.runSubprocess(cmd, T.ctx().env, pwd)
      CompilationResult(analysisFile, PathRef(classes))
    }

    def config = T {
      // Forcing the generation of the config for all modules here
      val _ = BloopModule.install() // NB: looks like it has to be assigned to be executed
      BloopModule.bloopConfig(pwd / ".bloop", self)()._1
    }

    // Converts from a Circe encoder to a uPickle one
    implicit def circeWriter[T: Encoder]: default.Writer[T] =
      new default.Writer[T] {
        override def write0[V](out: Visitor[_, V], v: T) =
          ujson.circe.CirceJson.transform(Encoder[T].apply(v), out)
      }

    // Converts from a Circe decoder to a uPickle one
    implicit def circeReader[T: Decoder]: default.Reader[T] =
      new default.Reader.Delegate[Json, T](
        ujson.circe.CirceJson.map(Decoder[T].decodeJson).map(_.right.get))
  }
}

object BloopModule extends ExternalModule {

  private val ev = Evaluator.currentEvaluator.get()
  implicit def millScoptEvaluatorReads[T] = new mill.main.EvaluatorScopt[T]()

  def install = T {
    val bloopDir = pwd / ".bloop"
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
