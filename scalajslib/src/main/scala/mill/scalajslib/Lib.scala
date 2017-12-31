package mill
package scalajslib

import java.io.File
import java.net.URLClassLoader

import ammonite.ops.{Path, mkdir, rm, _}
import mill.eval.PathRef
import mill.scalalib.Dep
import mill.util.Ctx

import scala.collection.breakOut
import scala.language.reflectiveCalls

private object LinkerBridge {
  @volatile var scalaInstanceCache = Option.empty[(Long, ScalaJSLinkerBridge)]
}

object Lib {

  def scalaJSLinkerIvyDep(scalaJSVersion: String): Dep =
    Dep("com.lihaoyi", s"mill-jsbridge_${scalaJSVersion.replace('.', '_')}", "0.1-SNAPSHOT")

  def scalaJSLinkerBridge(classPath: Seq[Path]): ScalaJSLinkerBridge = {
    val classloaderSig = classPath.map(p => p.toString().hashCode + p.mtime.toMillis).sum
    LinkerBridge.scalaInstanceCache match {
      case Some((`classloaderSig`, linker)) => linker
      case _ =>
        val cl = new URLClassLoader(classPath.map(_.toIO.toURI.toURL).toArray)
        val bridge = cl.loadClass("mill.scalajslib.bridge.ScalaJSLinkerBridge")
          .getDeclaredConstructor().newInstance().asInstanceOf[ {
          def link(sources: Array[File], libraries: Array[File], dest: File, main: String, fullOpt: Boolean): Unit
        }]
        val linker: ScalaJSLinkerBridge = (sources: Seq[File],
                                           libraries: Seq[File],
                                           dest: File,
                                           main: Option[String],
                                           mode: OptimizeMode) =>
          bridge.link(sources.toArray, libraries.toArray, dest, main.orNull, mode == FullOpt)
        LinkerBridge.scalaInstanceCache = Some((classloaderSig, linker))
        linker
    }
  }

  def link(main: Option[String],
           inputPaths: Seq[Path],
           libraries: Seq[Path],
           linker: ScalaJSLinkerBridge,
           mode: OptimizeMode)
          (implicit ctx: Ctx.DestCtx): PathRef = {
    val outputPath = ctx.dest.copy(segments = ctx.dest.segments.init :+ (ctx.dest.segments.last + ".js"))
    rm(outputPath)
    if (inputPaths.nonEmpty) {
      mkdir(outputPath / up)
      val inputFiles: Vector[File] = inputPaths.map(ls).flatMap(_.filter(_.ext == "sjsir")).map(_.toIO)(breakOut)
      val inputLibraries: Vector[File] = libraries.filter(_.ext == "jar").map(_.toIO)(breakOut)
      linker.link(inputFiles, inputLibraries, outputPath.toIO, main, mode)
    }
    PathRef(outputPath)
  }

}
