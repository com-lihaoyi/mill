package mill
package scalalib

import java.io.File

import ammonite.ops._
import coursier.{Cache, Fetch, MavenRepository, Repository, Resolution, Module => CoursierModule}
import mill.eval.{PathRef, Result}
import mill.util.Loose.Agg


object CompilationResult {
  implicit val jsonFormatter: upickle.default.ReadWriter[CompilationResult] = upickle.default.macroRW
}

// analysisFile is represented by Path, so we won't break caches after file changes
case class CompilationResult(analysisFile: Path, classes: PathRef)

object Lib{

  def scalaBinaryVersion(scalaVersion: String) = scalaVersion.split('.').dropRight(1).mkString(".")

  def grepJar(classPath: Agg[Path], s: String) = {
    classPath
      .find(_.toString.endsWith(s))
      .getOrElse(throw new Exception("Cannot find " + s))
      .toIO
  }

  /**
    * Resolve dependencies using Coursier.
    *
    * We do not bother breaking this out into the separate ScalaWorker classpath,
    * because Coursier is already bundled with mill/Ammonite to support the
    * `import $ivy` syntax.
    */
  def resolveDependencies(repositories: Seq[Repository],
                          scalaVersion: String,
                          deps: TraversableOnce[Dep],
                          sources: Boolean = false): Result[Agg[PathRef]] = {
    val flattened = deps.map{
      case Dep.Java(dep) => dep
      case Dep.Scala(dep) =>
        dep.copy(module = dep.module.copy(name = dep.module.name + "_" + scalaBinaryVersion(scalaVersion)))
      case Dep.Point(dep) =>
        dep.copy(module = dep.module.copy(name = dep.module.name + "_" + scalaVersion))
    }.toSet
    val start = Resolution(flattened)

    val fetch = Fetch.from(repositories, Cache.fetch())
    val resolution = start.process.run(fetch).unsafePerformSync
    val errs = resolution.metadataErrors
    if(errs.nonEmpty) {
      val header =
        s"""|
            |Resolution failed for ${errs.length} modules:
            |--------------------------------------------
            |""".stripMargin

      val errLines = errs.map {
        case ((module, vsn), errMsgs) => s"  ${module.trim}:$vsn \n\t" + errMsgs.mkString("\n\t")
      }.mkString("\n")
      val msg = header + errLines + "\n"
      Result.Failure(msg)
    } else {
      val sourceOrJar =
        if (sources) resolution.classifiersArtifacts(Seq("sources"))
        else resolution.artifacts
      val localArtifacts: Seq[File] = scalaz.concurrent.Task
        .gatherUnordered(sourceOrJar.map(Cache.file(_).run))
        .unsafePerformSync
        .flatMap(_.toOption)

      Agg.from(
        localArtifacts.map(p => PathRef(Path(p), quick = true)).filter(_.path.ext == "jar")
      )
    }
  }
  def scalaCompilerIvyDeps(scalaVersion: String) = Agg[Dep](
    Dep.Java("org.scala-lang", "scala-compiler", scalaVersion),
    Dep.Java("org.scala-lang", "scala-reflect", scalaVersion)
  )
  def scalaRuntimeIvyDeps(scalaVersion: String) = Agg[Dep](
    Dep.Java("org.scala-lang", "scala-library", scalaVersion)
  )
  def compilerBridgeIvyDep(scalaVersion: String) =
    Dep.Point(coursier.Dependency(coursier.Module("com.lihaoyi", "mill-bridge"), "0.1", transitive = false))

  val DefaultShellScript: Seq[String] = Seq(
    "#!/usr/bin/env sh",
    "exec java -jar \"$0\" \"$@\""
  )
}
