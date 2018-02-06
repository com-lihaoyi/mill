package mill
package scalajslib
package bridge

import java.io.File

import org.scalajs.core.tools.io.IRFileCache.IRContainer
import org.scalajs.core.tools.io._
import org.scalajs.core.tools.jsdep.ResolvedJSDependency
import org.scalajs.core.tools.linker.{ModuleInitializer, StandardLinker}
import org.scalajs.core.tools.logging.ScalaConsoleLogger
import org.scalajs.jsenv._
import org.scalajs.jsenv.nodejs._
import org.scalajs.testadapter.TestAdapter

class ScalaJSBridge extends mill.scalajslib.ScalaJSBridge {
  def link(sources: Array[File], libraries: Array[File], dest: File, main: String, fullOpt: Boolean): Unit = {
    val config = StandardLinker.Config().withOptimizer(fullOpt)
    val linker = StandardLinker(config)
    val sourceSJSIRs = sources.map(new FileVirtualScalaJSIRFile(_))
    val jars = libraries.map(jar => IRContainer.Jar(new FileVirtualBinaryFile(jar) with VirtualJarFile))
    val jarSJSIRs = jars.flatMap(_.jar.sjsirFiles)
    val destFile = AtomicWritableFileVirtualJSFile(dest)
    val logger = new ScalaConsoleLogger
    val initializer = Option(main).map { cls => ModuleInitializer.mainMethodWithArgs(cls, "main") }
    linker.link(sourceSJSIRs ++ jarSJSIRs, initializer.toSeq, destFile, logger)
  }

  def run(linkedFile: File): Unit = {
    new NodeJSEnv()
      .jsRunner(FileVirtualJSFile(linkedFile))
      .run(new ScalaConsoleLogger, ConsoleJSConsole)
  }

  def getFramework(frameworkName: String,
                   linkedFile: File): sbt.testing.Framework = {
    val env = new NodeJSEnv().loadLibs(
      Seq(ResolvedJSDependency.minimal(new FileVirtualJSFile(linkedFile)))
    )
    val jsConsole = ConsoleJSConsole
    val config = TestAdapter.Config().withLogger(new ScalaConsoleLogger)
    val adapter =
      new TestAdapter(env, config)

    adapter
      .loadFrameworks(List(List(frameworkName)))
      .flatten
      .headOption
      .getOrElse(throw new RuntimeException("Failed to get framework"))
  }
}
