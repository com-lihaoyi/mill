package mill
package scalajslib
package worker

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import java.io.File
import mill.api.{internal, Result}
import mill.scalajslib.api.{ModuleInitializer, ModuleKind, ModuleSplitStyle, OutputPatterns}

import org.scalajs.linker.{PathIRContainer, PathIRFile, PathOutputDirectory, StandardImpl}
import org.scalajs.linker.interface.{ModuleKind => ScalaJSModuleKind, _}
import org.scalajs.linker.interface.{ModuleSplitStyle => ScalaJSModuleSplitStyle}
import org.scalajs.linker.interface.{OutputPatterns => ScalaJSOutputPatterns}
import org.scalajs.logging.ScalaConsoleLogger
import org.scalajs.testing.adapter.{TestAdapterInitializer => TAI}

import scala.collection.mutable
import scala.ref.WeakReference

@internal
class ScalaJsLinker_1_3Support {
  private case class LinkerInput_1_3(
      fullOpt: Boolean,
      moduleKind: ModuleKind,
      moduleSplitStyle: ModuleSplitStyle,
      outputPatterns: OutputPatterns,
      useECMAScript2015: Boolean,
      dest: File
  )

  private object ScalaJSLinker_1_3 {
    private val cache = mutable.Map.empty[LinkerInput_1_3, WeakReference[Linker]]

    def reuseOrCreate(input: LinkerInput_1_3): Linker = cache.get(input) match {
      case Some(WeakReference(linker)) => linker
      case _ =>
        val newLinker = createLinker(input)
        cache.update(input, WeakReference(newLinker))
        newLinker
    }

    private def createLinker(input: LinkerInput_1_3): Linker = {
      val semantics = input.fullOpt match {
        case true => Semantics.Defaults.optimized
        case false => Semantics.Defaults
      }
      val scalaJSModuleKind = input.moduleKind match {
        case ModuleKind.NoModule => ScalaJSModuleKind.NoModule
        case ModuleKind.CommonJSModule => ScalaJSModuleKind.CommonJSModule
        case ModuleKind.ESModule => ScalaJSModuleKind.ESModule
      }
      val scalaJSModuleSplitStyle = input.moduleSplitStyle match {
        case ModuleSplitStyle.FewestModules => ScalaJSModuleSplitStyle.FewestModules
        case ModuleSplitStyle.SmallestModules => ScalaJSModuleSplitStyle.SmallestModules
      }
      val scalaJSOutputPatterns = input.outputPatterns match {
        case OutputPatterns.OutputPatternsDefaults => ScalaJSOutputPatterns.Defaults
        case OutputPatterns.OutputPatternsFromJsFile(jsFile) =>
          ScalaJSOutputPatterns.fromJSFile(jsFile)
      }

      val useClosure = input.fullOpt && input.moduleKind != ModuleKind.ESModule
      val config = StandardConfig()
        .withOptimizer(input.fullOpt)
        .withClosureCompilerIfAvailable(useClosure)
        .withSemantics(semantics)
        .withModuleKind(scalaJSModuleKind)
        .withModuleSplitStyle(scalaJSModuleSplitStyle)
        .withOutputPatterns(scalaJSOutputPatterns)
        .withESFeatures(_.withUseECMAScript2015(input.useECMAScript2015))
      StandardImpl.linker(config)
    }
  }

  def linkJs(
      sources: Array[File],
      libraries: Array[File],
      dest: File,
      main: Option[String],
      testBridgeInit: Boolean,
      fullOpt: Boolean,
      moduleKind: ModuleKind,
      moduleSplitStyle: ModuleSplitStyle,
      moduleInitializers: Seq[ModuleInitializer],
      outputPatterns: OutputPatterns,
      useECMAScript2015: Boolean
  ) = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val linker =
      ScalaJSLinker_1_3.reuseOrCreate(LinkerInput_1_3(
        fullOpt,
        moduleKind,
        moduleSplitStyle,
        outputPatterns,
        useECMAScript2015,
        dest
      ))
    val cache = StandardImpl.irFileCache().newCache
    val sourceIRsFuture = Future.sequence(sources.toSeq.map(f => PathIRFile(f.toPath())))
    val irContainersPairs = PathIRContainer.fromClasspath(libraries.map(_.toPath()))
    val libraryIRsFuture = irContainersPairs.flatMap(pair => cache.cached(pair._1))
    val logger = new ScalaConsoleLogger

    val mainInitializer = main.map(cls =>
      org.scalajs.linker.interface.ModuleInitializer.mainMethodWithArgs(
        className = cls,
        mainMethodName = "main"
      )
    )

    val otherInitializers = moduleInitializers.map(mi =>
      org.scalajs.linker.interface.ModuleInitializer.mainMethod(
        className = mi.className,
        mainMethodName = mi.mainMethod
      ).withModuleID(mi.moduleId)
    )

    val testInitializer =
      if (testBridgeInit)
        Some(org.scalajs.linker.interface.ModuleInitializer.mainMethod(
          TAI.ModuleClassName,
          TAI.MainMethodName
        ))
      else None
    val allModuleInitializers =
      mainInitializer.toList ::: otherInitializers.toList ::: testInitializer.toList
    val linkerOutputDirectory = PathOutputDirectory(dest.toPath)
    val resultFuture = (for {
      sourceIRs <- sourceIRsFuture
      libraryIRs <- libraryIRsFuture
      _ <-
        linker.link(sourceIRs ++ libraryIRs, allModuleInitializers, linkerOutputDirectory, logger)
    } yield {
      Result.Success(dest)
    }).recover {
      case e: org.scalajs.linker.interface.LinkingException =>
        Result.Failure(e.getMessage)
    }
    Await.result(resultFuture, Duration.Inf)
  }
}
