package mill
package scalajslib
package bridge

import java.io.File

import org.scalajs.core.tools.io.IRFileCache.IRContainer
import org.scalajs.core.tools.io.{AtomicWritableFileVirtualJSFile, FileVirtualBinaryFile, FileVirtualScalaJSIRFile, VirtualJarFile}
import org.scalajs.core.tools.linker.{ModuleInitializer, StandardLinker}
import org.scalajs.core.tools.logging.ScalaConsoleLogger

class ScalaJSLinkerBridge extends mill.scalajslib.ScalaJSLinkerBridge{
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
}