package mill
package scalajslib
package bridge

import java.io.File

import org.scalajs.core.tools.io._
import org.scalajs.core.tools.linker.{ModuleInitializer, StandardLinker}
import org.scalajs.core.tools.logging.ScalaConsoleLogger

class ScalaJSLinkerBridge extends mill.scalajslib.ScalaJSLinkerBridge{
  def link(sources: Array[File], libraries: Array[File], dest: File, main: String, fullOpt: Boolean): Unit = {
    val config = StandardLinker.Config().withOptimizer(fullOpt)
    val linker = StandardLinker(config)
    val cache = new IRFileCache().newCache
    val sourceIRs = sources.map(FileVirtualScalaJSIRFile)
    val irContainers = FileScalaJSIRContainer.fromClasspath(libraries)
    val libraryIRs = cache.cached(irContainers)
    val destFile = AtomicWritableFileVirtualJSFile(dest)
    val logger = new ScalaConsoleLogger
    val initializer = Option(main).map { cls => ModuleInitializer.mainMethodWithArgs(cls, "main") }
    linker.link(sourceIRs ++ libraryIRs, initializer.toSeq, destFile, logger)
  }
}