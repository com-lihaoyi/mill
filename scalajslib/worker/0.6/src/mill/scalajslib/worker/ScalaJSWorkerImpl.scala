package mill
package scalajslib
package worker

import java.io.File

import mill.eval.Result
import org.scalajs.core.tools.io.IRFileCache.IRContainer
import org.scalajs.core.tools.io._
import org.scalajs.core.tools.jsdep.ResolvedJSDependency
import org.scalajs.core.tools.linker.{ModuleInitializer, StandardLinker, Semantics, ModuleKind => ScalaJSModuleKind}
import org.scalajs.core.tools.logging.ScalaConsoleLogger
import org.scalajs.jsenv._
import org.scalajs.jsenv.nodejs._
import org.scalajs.testadapter.TestAdapter

class ScalaJSWorkerImpl extends mill.scalajslib.ScalaJSWorkerApi {
  def link(sources: Array[File],
           libraries: Array[File],
           dest: File,
           main: String,
           fullOpt: Boolean,
           moduleKind: ModuleKind) = {
    val semantics = fullOpt match {
        case true => Semantics.Defaults.optimized
        case false => Semantics.Defaults
    }
    val scalaJSModuleKind = moduleKind match {
      case ModuleKind.NoModule => ScalaJSModuleKind.NoModule
      case ModuleKind.CommonJSModule => ScalaJSModuleKind.CommonJSModule
    }
    val config = StandardLinker.Config()
      .withOptimizer(fullOpt)
      .withClosureCompilerIfAvailable(fullOpt)
      .withSemantics(semantics)
      .withModuleKind(scalaJSModuleKind)
    val linker = StandardLinker(config)
    val sourceSJSIRs = sources.map(new FileVirtualScalaJSIRFile(_))
    val jars = libraries.map(jar => IRContainer.Jar(new FileVirtualBinaryFile(jar) with VirtualJarFile))
    val jarSJSIRs = jars.flatMap(_.jar.sjsirFiles)
    val destFile = AtomicWritableFileVirtualJSFile(dest)
    val logger = new ScalaConsoleLogger
    val initializer = Option(main).map { cls => ModuleInitializer.mainMethodWithArgs(cls, "main") }
    try {
      linker.link(sourceSJSIRs ++ jarSJSIRs, initializer.toSeq, destFile, logger)
      Result.Success(dest)
    }catch {case e: org.scalajs.core.tools.linker.LinkingException =>
      Result.Failure(e.getMessage)
    }
  }

  def run(config: NodeJSConfig, linkedFile: File): Unit = {
    nodeJSEnv(config)
      .jsRunner(FileVirtualJSFile(linkedFile))
      .run(new ScalaConsoleLogger, ConsoleJSConsole)
  }

  def getFramework(config: NodeJSConfig,
                   frameworkName: String,
                   linkedFile: File): (() => Unit, sbt.testing.Framework) = {
    val env = nodeJSEnv(config).loadLibs(
      Seq(ResolvedJSDependency.minimal(new FileVirtualJSFile(linkedFile)))
    )

    val tconfig = TestAdapter.Config().withLogger(new ScalaConsoleLogger)
    val adapter =
      new TestAdapter(env, tconfig)

    (
      () => adapter.close(),
      adapter
        .loadFrameworks(List(List(frameworkName)))
        .flatten
        .headOption
        .getOrElse(throw new RuntimeException("Failed to get framework"))
    )
  }

  def nodeJSEnv(config: NodeJSConfig): NodeJSEnv = {
    new NodeJSEnv(
      NodeJSEnv.Config()
        .withExecutable(config.executable)
        .withArgs(config.args)
        .withEnv(config.env)
        .withSourceMap(config.sourceMap))
  }
}
