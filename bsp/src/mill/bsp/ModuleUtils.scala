package mill.bsp

import ammonite.runtime.SpecialClassLoader
import ch.epfl.scala.bsp4j._
import coursier.Resolve
import java.net.URL
import mill._
import mill.api.Result.Success
import mill.api.{PathRef, Strict}
import mill.define._
import mill.eval.{Evaluator, _}
import mill.scalajslib.ScalaJSModule
import mill.scalalib._
import mill.scalalib.Lib.{depToDependency, resolveDependencies, scalaRuntimeIvyDeps}
import mill.scalalib.api.Util
import mill.scalanativelib._
import mill.api.Ctx
import os.{Path, exists}
import scala.util.Try
import scala.jdk.CollectionConverters._

/**
 * Utilities for translating the mill build into
 * BSP information like BuildTargets and BuildTargetIdentifiers
 */
object ModuleUtils {

  /**
   * Compute the BuildClasspath for the Mill build (build.sc files)
   *
   * @param evaluator mill evaluator that can resolve build information
   * @param sources classpath for source jars or not
   * @return Mill build Classpath(URI)
   */
  def getMillBuildClasspath(evaluator: Evaluator, sources: Boolean): Seq[String] = {

    /**
     * On Windows, URLs follow an peculiar representation in Java
     * scala> java.nio.file.Paths.get(".").toAbsolutePath.toUri.toURL
     * java.net.URL = file:/C:/Users/Developer/mill/./
     *
     * From this, we wish to get a Path back, and the way to do this is:
     *
     * scala> java.nio.file.Paths.get((java.nio.file.Paths.get(".").toAbsolutePath.toUri.toURL).toURI)
     * java.nio.file.Path = C:\Users\Developer\mill\.
     *
     * Unit testing for this is more challenging because the WindowsFileSystem instance is a sun.nio.fs package,
     * rather than a standard package.
     *
     * The solution here, compared to the previous code, is to reduce the number of conversions;
     * the key loss happens when you do (URL).getFile.
     * scala> java.nio.file.Paths.get(".").toAbsolutePath.toUri.toURL
     * java.net.URL = file:/C:/Users/Developer/mill/./
     * scala> java.nio.file.Paths.get(".").toAbsolutePath.toUri.toURL.getFile
     * String = /C:/Users/Developer/mill/./
     *
     * It works for @camper42 on MacOS & Windows IDEA, works for @fabianhjr on Linux(NixOS)
     */
    val classpath: Seq[Path] = Try(
      evaluator.rootModule.getClass.getClassLoader
        .asInstanceOf[SpecialClassLoader]
    )
      .fold(_ => Seq.empty, _.allJars)
      .map(url => Path(java.nio.file.Paths.get(url.toURI)))

    val millJars: Seq[Path] = resolveDependencies(
      Resolve.defaultRepositories,
      depToDependency(_, BuildInfo.scalaVersion),
      BuildInfo.millEmbeddedDeps.map(d => ivy"$d"),
      sources = sources
    ).asSuccess.toSeq.flatMap(_.value).map(_.path)

    val all = classpath ++ millJars
    val binarySource =
      if (sources) all.filter(url => isPathSourceJar(url))
      else all.filter(url => !isPathSourceJar(url))
    binarySource.filter(path => exists(path)).map(_.toNIO.toUri.toString)
  }

  def isPathSourceJar(path: Path): Boolean =
    path.wrapped.toString.endsWith("-sources.jar")

}
