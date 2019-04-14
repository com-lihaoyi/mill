package mill.contrib.bloop

import java.io.{PipedInputStream, PipedOutputStream}

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
import scribe.{LogRecord, LoggerSupport}
import upickle.core.Visitor
import upickle.default

import scala.meta.jsonrpc.{JsonRpcClient, Services}

// TODO can this be mixed with other scalajs module safely ?
// TODO can we plugin into "mill clean" ?
//
trait BloopModule extends ScalaModule { self =>

  trait Tests extends super.Tests with BloopModule {
    override def test(args: String*) = {
      super.test(args: _*)
    }
  }

  override def compile = T {
    val config = bloopConfig().project
    val cmd = Seq("bloop", "compile", config.name)
    val classes = Path(config.classesDir)
    val out = Path(config.out)
    val analysisFile = out / s"${config.name}-analysis.bin" // TODO does it need polling ?
    Jvm.runSubprocess(cmd, T.ctx().env, pwd)
    CompilationResult(analysisFile, PathRef(classes))
  }

  override def run(args: String*) = T.command {
    val config = bloopConfig().project
    val cmd = Seq("bloop", "run", config.name) ++ args // TODO does bloop return a specific exit code when failing to parse params ?
    Jvm.runSubprocess(cmd, T.ctx().env, pwd)
  }

  def bloopConfig = T {
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

object BloopModule extends ExternalModule {

  val bloopVersion = "1.2.5"

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
      .traverse(modules)(_.sources)
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
        m.resources().map(_.path) ++
        m.unmanagedClasspath().map(_.path) ++
        Task.traverse(m.moduleDeps)(transitiveClasspath)().flatten
    }

    val classpath = T.task(transitiveClasspath(module)() ++ ivyDepsClasspath())

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
        resources = None,
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

  /**
    * Creates the connection to bloop and surfaces a client
    */
  def bloopConnection: Worker[JsonRpcClient] = T.worker {
    import bloop.launcher._

    val launcherIn = new PipedInputStream()
    val clientOut = new PipedOutputStream(launcherIn)

    val clientIn = new PipedInputStream()
    val launcherOut = new PipedOutputStream(clientIn)

    val startedServer = scala.concurrent.Promise[Unit]()
    val charset = java.nio.charset.StandardCharsets.UTF_8
    val shell = bloop.launcher.core.Shell.default
    val launcher = new LauncherMain(clientIn,
                                    clientOut,
                                    T.ctx().log.outputStream,
                                    charset,
                                    shell,
                                    None,
                                    startedServer)
    val skipBspConnection = false
    launcher.runLauncher(bloopVersion, skipBspConnection, List.empty) match {
      case LauncherStatus.SuccessfulRun => ()
      case other                        => throw new Exception("Bloop connection failed with: $other")
    }
    val millLogger = T.ctx().log
    val logger = new LoggerSupport {
      override def log[M](record: LogRecord[M]): Unit =
        millLogger.debug(record.message) // might need some more thoughts
    }
    import monix.execution.Scheduler.Implicits.global
    val clientIO = new scala.meta.jsonrpc.InputOutput(launcherIn, launcherOut)
    val clientServices =
      (_: scala.meta.jsonrpc.LanguageClient) => Services.empty(logger)
    val connection =
      scala.meta.jsonrpc.Connection(clientIO, logger, logger)(clientServices)
    connection.client
  }

  lazy val millDiscover = Discover[this.type]
}
