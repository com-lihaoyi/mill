package forge
package scalaplugin

import ammonite.ops.Path
import forge.{Target => T}
import forge.util.PathRef
object Subproject{
  def compileScala(sources: T[PathRef],
                   dependencyClasspath: T[Seq[PathRef]],
                   outputPath: T[Path]): T[PathRef] = ???
  def createJar(sourceDirs: T[Seq[PathRef]]) = ???
  def resolveDependencies(deps: T[Seq[coursier.Dependency]]): T[Seq[PathRef]] = ???
}
import Subproject._
abstract class Subproject {
  val scalaVersion: T[String]

  val compileDeps: T[Seq[coursier.Dependency]]
  val runDeps: T[Seq[coursier.Dependency]]
  val basePath: T[Path]

  val compileDepClasspath: T[Seq[PathRef]] = resolveDependencies(compileDeps)
  val runDepClasspath: T[Seq[PathRef]] = resolveDependencies(runDeps)
  val sources: T[PathRef] = basePath.map(p => PathRef(p / 'src))
  val outputPath: T[Path] = basePath.map(p => p / 'out)
  val resources: T[PathRef] = basePath.map(p => PathRef(p / 'resources))
  val compiledPath: T[Path] = outputPath.map(p => p / 'classpath)
  val compiled: T[PathRef] = compileScala(sources, compileDepClasspath, outputPath)
  val classpath: T[Seq[PathRef]] = for((r, c) <- resources.zip(compiled)) yield Seq(r, c)
  val jar: T[PathRef] = createJar(classpath)
}
