package mill.contrib

import scala.collection.mutable
import scala.collection.JavaConverters.enumerationAsScalaIteratorConverter
import scala.util.matching.Regex
import java.io.InputStreamReader
import java.io.File
import java.util.jar.JarFile

import mill._
import mill.scalajslib.ScalaJSModule
import ammonite.ops._
import mill.define.Target
import org.scalajs.jsdependencies.core._
import org.scalajs.io._


trait ScalaJSDependencies extends ScalaJSModule {

  def jsdependenciesOutputFileName = T {
    val infix = if(useMinifiedJSDependencies()) "-min" else ""
    s"out-deps${infix}.js"
  }

  def useMinifiedJSDependencies = T {
    false
  }

  override def fastOpt = T {
    packageJSDependencies()
    super.fastOpt()
  }

  override def fullOpt = T {
    packageJSDependencies()
    super.fullOpt()
  }


  def packageJSDependencies = T {
    //    val directDeps = ??? // Todo: ProvidedJS equivalent for build file
    val cpdeps = resolvedJSDependencies(compileClasspath().filter(p => exists(p.path)))
    val output = T.ctx().dest / jsdependenciesOutputFileName()
    val selectLib = if (useMinifiedJSDependencies())
      (dep: ResolvedJSDependency) => dep.minifiedLib.getOrElse(dep.lib)
    else
      (dep: ResolvedJSDependency) => dep.lib

    for (dep <- cpdeps) {
      write.append(output, selectLib(dep).readLines().map(_ + ammonite.util.Util.newLine))
    }
    PathRef(output)
  }


  // code mainly copied & adapted from https://github.com/scala-js/jsdependencies
  private def collectFromClasspath[T](cp: Agg[PathRef],
                                      filter: Regex,
                                      collectJar: Path => Seq[T],
                                      collectFile: (File, String) => T): Seq[T] = {
    val results = Seq.newBuilder[T]
    for {
      pathRef <- cp
      cpEntry = pathRef.path
    } {
      if (cpEntry.isFile && cpEntry.name.endsWith(".jar")) {
        results ++= collectJar(cpEntry)
      } else if (cpEntry.isDir) {
        for (
          (file, relPath) <-
            ls.rec !
              cpEntry |? { p => filter.findFirstMatchIn(p.name).isDefined } | { p => (p.toIO, p.relativeTo(cpEntry).toString()) }
        ) {
          results += collectFile(file, relPath)
        }
      } else {
        throw new IllegalArgumentException("Illegal classpath entry: " + cpEntry.toNIO.toString)
      }
    }
    results.result()
  }

  private def jsDependencyManifestsInJar(container: Path): List[JSDependencyManifest] = {
    val jar = new JarFile(container.toIO)
    jar
      .entries()
      .asScala
      .filter(_.getName == JSDependencyManifest.ManifestFileName)
      .map(m => JSDependencyManifest.read(new InputStreamReader(jar.getInputStream(m), "UTF-8")))
      .toList
  }

  private def jsDependencyManifests(ccp: Agg[PathRef]) = {
    collectFromClasspath(
      ccp,
      JSDependencyManifest.ManifestFileName.r,
      collectJar = jsDependencyManifestsInJar,
      collectFile = { (file, _) => JSDependencyManifest.read(FileVirtualTextFile(file)) })
  }

  private def jsFilesInJar(container: Path): List[VirtualJSFile with RelativeVirtualFile] = {
    val jar = new JarFile(container.toIO)
    jar
      .entries()
      .asScala
      .filter(_.getName.endsWith(".js"))
      .map(m => {
        val file = new EntryJSFile(container.name, m.getName)
        file.content = org.scalajs.io.IO.readInputStreamToString(jar.getInputStream(m))
        file.version = None
        file
      })
      .toList
  }

  private class EntryJSFile(outerPath: String, val relativePath: String)
    extends MemVirtualJSFile(s"$outerPath:$relativePath")
      with RelativeVirtualFile

  private def scalaJSNativeLibraries(ccp: Agg[PathRef]) = {
    collectFromClasspath(
      ccp,
      ".*\\.js$".r,
      collectJar = jsFilesInJar,
      collectFile = FileVirtualJSFile.relative)
  }

  private def resolvedJSDependencies(ccp: Agg[PathRef]) = {
    val attLibs = scalaJSNativeLibraries(ccp)
    val attManifests = jsDependencyManifests(ccp)

    // Collect available JS libraries
    val availableLibs: Map[String, VirtualJSFile] = {
      val libs = mutable.Map.empty[String, VirtualJSFile]
      for (lib <- attLibs)
        libs.getOrElseUpdate(lib.relativePath, lib)
      libs.toMap
    }

    // Actually resolve the dependencies
    DependencyResolver
      .resolveDependencies(
        attManifests,
        availableLibs,
        identity(_)) // filter for js files -> not sure if this is needed
  }


}

