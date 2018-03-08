package mill
package scalalib

import ammonite.ops._
import ammonite.util.Util
import coursier.{Cache, Dependency, Fetch, Repository, Resolution}
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

  def depToDependency(dep: Dep, scalaVersion: String, platformSuffix: String = ""): Dependency =
    dep match {
      case Dep.Java(dep, cross) =>
        dep.copy(
          module = dep.module.copy(
            name =
              dep.module.name +
              (if (!cross) "" else platformSuffix)
          )
        )
      case Dep.Scala(dep, cross) =>
        dep.copy(
          module = dep.module.copy(
            name =
              dep.module.name +
              (if (!cross) "" else platformSuffix) +
              "_" + scalaBinaryVersion(scalaVersion)
          )
        )
      case Dep.Point(dep, cross) =>
        dep.copy(
          module = dep.module.copy(
            name =
              dep.module.name +
              (if (!cross) "" else platformSuffix) +
              "_" + scalaVersion
          )
        )
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
                          platformSuffix: String = "",
                          sources: Boolean = false): Result[Agg[PathRef]] = {

    val flattened = deps.map(depToDependency(_, scalaVersion, platformSuffix)).toSet
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

      def load(artifacts: Seq[coursier.Artifact]) = {
        val logger = None
        val loadedArtifacts = scalaz.concurrent.Task.gatherUnordered(
          for (a <- artifacts)
            yield coursier.Cache.file(a, logger = logger).run
              .map(a.isOptional -> _)
        ).unsafePerformSync

        val errors = loadedArtifacts.collect {
          case (false, scalaz.-\/(x)) => x
          case (true, scalaz.-\/(x)) if !x.notFound => x
        }
        val successes = loadedArtifacts.collect { case (_, scalaz.\/-(x)) => x }
        (errors, successes)
      }

      val sourceOrJar =
        if (sources) resolution.classifiersArtifacts(Seq("sources"))
        else resolution.artifacts(true)
      val (errors, successes) = load(sourceOrJar)
      if(errors.isEmpty){
        Agg.from(
          successes.map(p => PathRef(Path(p), quick = true)).filter(_.path.ext == "jar")
        )
      }else{
        val errorDetails = errors.map(e => s"${Util.newLine}  ${e.describe}").mkString
        Result.Failure("Failed to load source dependencies" + errorDetails)
      }
    }
  }
  def scalaCompilerIvyDeps(scalaVersion: String) = Agg[Dep](
    ivy"org.scala-lang:scala-compiler:$scalaVersion",
    ivy"org.scala-lang:scala-reflect:$scalaVersion"
  )
  def scalaRuntimeIvyDeps(scalaVersion: String) = Agg[Dep](
    ivy"org.scala-lang:scala-library:$scalaVersion"
  )
  def compilerBridgeIvyDep(scalaVersion: String) =
    Dep.Point(
      coursier.Dependency(coursier.Module("com.lihaoyi", "mill-bridge"), "0.1", transitive = false),
      cross = false
    )

}
