package mill.scalalib.bsp

import coursier.Repository
import mill.api.{PathRef, internal}
import mill.define.{Command, Task}
import mill.*
import mill.scalalib.CoursierModule

trait BspModule extends Module {
  import BspModule._

  def sources: T[Seq[PathRef]]
  def allSourceFiles: T[Seq[PathRef]]
  def generatedSources: T[Seq[PathRef]]
  def bspBuildTargetSources: Task[(Seq[os.Path], Seq[os.Path])] = Task.Anon {
    Tuple2(sources().map(_.path), generatedSources().map(_.path))
  }

  def sanitizeUri(uri: String): String =
    if (uri.endsWith("/")) sanitizeUri(uri.substring(0, uri.length - 1)) else uri

  def sanitizeUri(uri: os.Path): String = sanitizeUri(uri.toNIO.toUri.toString)

  def sanitizeUri(uri: PathRef): String = sanitizeUri(uri.path)

  def bspBuildTargetInverseSources[T](id: T, searched: String): Task[Seq[T]] = Task.Anon {
    val src = allSourceFiles()
    val found = src.map(sanitizeUri).contains(searched)
    if (found) Seq(id) else Seq()
  }

  def millResolver: Task[CoursierModule.Resolver]
  def allRepositories: Task[Seq[Repository]]
  def unmanagedClasspath: T[Seq[PathRef]]
  def coursierDependency: coursier.core.Dependency
  def bspBuildTargetDependencySources(includeSources: Boolean) = Task.Anon {
    val repos = allRepositories()
    val buildSources = if (!includeSources) Nil
    else mill.scalalib.Lib
      .resolveMillBuildDeps(repos, None, useSources = true)
      .map(sanitizeUri(_))

    (
      millResolver().classpath(
        Seq(
          coursierDependency.withConfiguration(coursier.core.Configuration.provided),
          coursierDependency
        ),
        sources = true
      ),
      unmanagedClasspath(),
      buildSources
    )
  }

  def bspBuildTargetDependencyModules = Task.Anon {
    (
      // full list of dependencies, including transitive ones
      millResolver()
        .resolution(
          Seq(
            coursierDependency.withConfiguration(coursier.core.Configuration.provided),
            coursierDependency
          )
        )
        .orderedDependencies,
      unmanagedClasspath()
    )
  }

  def run(args: Task[Args] = Task.Anon(Args())): Command[Unit]
  def bspRun(args: Seq[String]): Command[Unit] = Task.Command {
    run(Task.Anon(Args(args)))()
  }

  def bspDisplayName0: String = this.moduleSegments.render

  def bspDisplayName: String = bspDisplayName0 match {
    case "" => "root-module"
    case n => n
  }

  /** Use to fill most fields of `BuildTarget`. */
  @internal
  def bspBuildTarget: BspBuildTarget = BspBuildTarget(
    displayName = Some(bspDisplayName),
    baseDirectory = Some(moduleDir),
    tags = Seq(Tag.Library, Tag.Application),
    languageIds = Seq(),
    canCompile = false,
    canTest = false,
    canRun = false,
    canDebug = false
  )

  /**
   * Use to populate the `BuildTarget.{dataKind,data}` fields.
   *
   * Mill specific implementations:
   * - [[JvmBuildTarget]]
   * - [[ScalaBuildTarget]]
   */
  @internal
  def bspBuildTargetData: Task[Option[(String, AnyRef)]] = Task.Anon { None }

}

object BspModule {

  /** Used to define the [[BspBuildTarget.languageIds]] field. */
  object LanguageId {
    val Java = "java"
    val Scala = "scala"
    val Kotlin = "kotlin"
  }

  /** Used to define the [[BspBuildTarget.tags]] field. */
  object Tag {
    val Library = "library"
    val Application = "application"
    val Test = "test"
    val IntegrationTest = "integration-test"
    val Benchmark = "benchmark"
    val NoIDE = "no-ide"
    val Manual = "manual"
  }
}
