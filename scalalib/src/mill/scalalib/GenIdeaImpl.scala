package mill.scalalib

import mill.Agg
import mill.api.Ctx.{Home, Log}
import mill.api.Strict
import mill.define._
import mill.eval.Evaluator
import mill.scalalib.GenIdeaModule.{IdeaConfigFile, JavaFacet}
import os.Path

import scala.xml.Elem

/**
 * This class is outdated and unmaintained. Please use [[mill.idea.GenIdeaImpl]] instead.
 */
@deprecated("Use mill.idea.GenIdeaImpl instead", "Mill 0.11.2")
case class GenIdeaImpl(
    evaluator: Evaluator,
    ctx: Log with Home,
    rootModule: BaseModule,
    discover: Discover
) {
  import GenIdeaImpl._

  val workDir: Path = rootModule.millSourcePath
  val ideaDir: Path = workDir / ".idea"

  val ideaConfigVersion = 4

  def run(): Unit = ???

  def extractCurrentJdk(ideaPath: os.Path): Option[(String, String)] = ???

  def xmlFileLayout(
      evaluator: Evaluator,
      rootModule: mill.Module,
      jdkInfo: (String, String),
      ctx: Option[Log],
      fetchMillModules: Boolean = true
  ): Seq[(os.SubPath, scala.xml.Node)] = ???

  def relify(p: os.Path): String = ???

  def ideaConfigElementTemplate(element: GenIdeaModule.Element): Elem = ???

  def ideaConfigFileTemplate(
      components: Map[Option[String], Seq[GenIdeaModule.Element]]
  ): Elem = ???

  def scalaSettingsTemplate(): Elem = ???

  def miscXmlTemplate(jdkInfo: (String, String)): Elem = ???

  def allModulesXmlTemplate(selectors: Seq[String]): Elem = ???

  def rootXmlTemplate(libNames: Strict.Agg[String]): scala.xml.Elem = ???

  /** Try to make the file path a relative JAR URL (to PROJECT_DIR). */
  def relativeJarUrl(path: os.Path): String = ???

  /** Try to make the file path a relative URL (to PROJECT_DIR). */
  def relativeFileUrl(path: Path): String = ???

  def libraryXmlTemplate(
      name: String,
      path: os.Path,
      sources: Option[os.Path],
      scalaCompilerClassPath: Agg[Path],
      languageLevel: Option[String]
  ): Elem = ???

  def moduleXmlTemplate(
      basePath: os.Path,
      scalaVersionOpt: Option[String],
      resourcePaths: Strict.Agg[os.Path],
      normalSourcePaths: Strict.Agg[os.Path],
      generatedSourcePaths: Strict.Agg[os.Path],
      compileOutputPath: os.Path,
      libNames: Seq[ScopedOrd[String]],
      depNames: Seq[ScopedOrd[String]],
      isTest: Boolean,
      facets: Seq[GenIdeaModule.JavaFacet]
  ): Elem = ???

  def scalaCompilerTemplate(
      settings: Map[(Agg[os.Path], Seq[String]), Seq[JavaModule]]
  ): Elem = ???
}

object GenIdeaImpl {

  /**
   * Create the module name (to be used by Idea) for the module based on it segments.
   * @see [[Module.millModuleSegments]]
   */
  def moduleName(p: Segments): String = ???
  sealed trait ResolvedLibrary { def path: os.Path }
  final case class CoursierResolved(path: os.Path, pom: os.Path, sources: Option[os.Path])
      extends ResolvedLibrary
  final case class OtherResolved(path: os.Path) extends ResolvedLibrary
  final case class WithSourcesResolved(path: os.Path, sources: Option[os.Path])
      extends ResolvedLibrary

  final case class Scoped[T](value: T, scope: Option[String])

  final case class ScopedOrd[T <: Comparable[T]](value: T, scope: Option[String])
      extends Ordered[ScopedOrd[T]] {
    override def compare(that: ScopedOrd[T]): Int = ???
  }
  object ScopedOrd {
    def apply[T <: Comparable[T]](scoped: Scoped[T]): ScopedOrd[T] = ???
  }

  final case class ResolvedModule(
      path: Segments,
      classpath: Agg[Scoped[Path]],
      module: JavaModule,
      pluginClasspath: Agg[Path],
      scalaOptions: Seq[String],
      compilerClasspath: Agg[Path],
      libraryClasspath: Agg[Path],
      facets: Seq[JavaFacet],
      configFileContributions: Seq[IdeaConfigFile],
      compilerOutput: Path
  )

  case class GenIdeaException(msg: String) extends RuntimeException

}
