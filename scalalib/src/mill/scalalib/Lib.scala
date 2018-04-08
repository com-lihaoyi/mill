package mill
package scalalib

import java.io.File
import javax.tools.ToolProvider

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
  def compileJava(sources: Array[java.io.File],
                  classpath: Array[java.io.File],
                  javaOpts: Seq[String],
                  upstreamCompileOutput: Seq[CompilationResult])
                 (implicit ctx: mill.util.Ctx) = {
    val javac = ToolProvider.getSystemJavaCompiler()

    rm(ctx.dest / 'classes)
    mkdir(ctx.dest / 'classes)
    val cpArgs =
      if(classpath.isEmpty) Seq()
      else Seq("-cp", classpath.mkString(File.pathSeparator))

    val args = Seq("-d", ctx.dest / 'classes) ++ cpArgs ++ javaOpts ++ sources

    javac.run(
      ctx.log.inStream, ctx.log.outputStream, ctx.log.errorStream,
      args.map(_.toString):_*
    )
    if (ls(ctx.dest / 'classes).isEmpty) mill.eval.Result.Failure("Compilation Failed")
    else mill.eval.Result.Success(CompilationResult(ctx.dest / 'zinc, PathRef(ctx.dest / 'classes)))
  }

  private val ReleaseVersion = raw"""(\d+)\.(\d+)\.(\d+)""".r
  private val MinorSnapshotVersion = raw"""(\d+)\.(\d+)\.([1-9]\d*)-SNAPSHOT""".r

  def scalaBinaryVersion(scalaVersion: String) = {
    scalaVersion match {
      case ReleaseVersion(major, minor, _) => s"$major.$minor"
      case MinorSnapshotVersion(major, minor, _) => s"$major.$minor"
      case _ => scalaVersion
    }
  }

  def grepJar(classPath: Agg[Path], s: String) = {
    classPath
      .find(_.toString.endsWith(s))
      .getOrElse(throw new Exception("Cannot find " + s))
      .toIO
  }


  def depToDependencyJava(dep: Dep, platformSuffix: String = ""): Dependency = {
    dep match {
      case Dep.Java(dep, cross, force) =>
        dep.copy(
          module = dep.module.copy(
            name =
              dep.module.name +
                (if (!cross) "" else platformSuffix)
          )
        )
    }
  }
  def depToDependency(dep: Dep, scalaVersion: String, platformSuffix: String = ""): Dependency =
    dep match {
      case d: Dep.Java => depToDependencyJava(dep)
      case Dep.Scala(dep, cross, force) =>
        dep.copy(
          module = dep.module.copy(
            name =
              dep.module.name +
              (if (!cross) "" else platformSuffix) +
              "_" + scalaBinaryVersion(scalaVersion)
          )
        )
      case Dep.Point(dep, cross, force) =>
        dep.copy(
          module = dep.module.copy(
            name =
              dep.module.name +
              (if (!cross) "" else platformSuffix) +
              "_" + scalaVersion
          )
        )
    }


  def resolveDependenciesMetadata(repositories: Seq[Repository],
                                  depToDependency: Dep => coursier.Dependency,
                                  deps: TraversableOnce[Dep],
                                  mapDependencies: Option[Dependency => Dependency] = None) = {
    val depSeq = deps.toSeq
    val flattened = depSeq.map(depToDependency)

    val forceVersions = depSeq.filter(_.force)
      .map(depToDependency)
      .map(mapDependencies.getOrElse(identity[Dependency](_)))
      .map{d => d.module -> d.version}
      .toMap

    val start = Resolution(
      flattened.map(mapDependencies.getOrElse(identity[Dependency](_))).toSet,
      forceVersions = forceVersions,
      mapDependencies = mapDependencies
    )

    val fetch = Fetch.from(repositories, Cache.fetch())
    val resolution = start.process.run(fetch).unsafePerformSync
    (flattened, resolution)
  }
  /**
    * Resolve dependencies using Coursier.
    *
    * We do not bother breaking this out into the separate ScalaWorker classpath,
    * because Coursier is already bundled with mill/Ammonite to support the
    * `import $ivy` syntax.
    */
  def resolveDependencies(repositories: Seq[Repository],
                          depToDependency: Dep => coursier.Dependency,
                          deps: TraversableOnce[Dep],
                          sources: Boolean = false,
                          mapDependencies: Option[Dependency => Dependency] = None): Result[Agg[PathRef]] = {

    val (_, resolution) = resolveDependenciesMetadata(
      repositories, depToDependency, deps, mapDependencies
    )
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
    ivy"org.scala-lang:scala-compiler:$scalaVersion".forceVersion(),
    ivy"org.scala-lang:scala-reflect:$scalaVersion".forceVersion()
  )
  def scalaRuntimeIvyDeps(scalaVersion: String) = Agg[Dep](
    ivy"org.scala-lang:scala-library:$scalaVersion".forceVersion()
  )
  def compilerBridgeIvyDep(scalaVersion: String) =
    Dep.Point(
      coursier.Dependency(coursier.Module("com.lihaoyi", "mill-bridge"), "0.1", transitive = false),
      cross = false,
      force = false
    )

}
