package mill
package javalib

import com.lihaoyi.unroll
import coursier.core.BomDependency
import coursier.params.ResolutionParams
import coursier.util.Task
import coursier.{Dependency, Repository, Resolution, Type}
import mill.api.{TaskCtx, PathRef}
import mill.api.Result
import mill.javalib.api.JvmWorkerUtil.*

/**
 * Utilities around managing JVM dependencies
 */
object Lib {
  def depToDependencyJava(dep: Dep, platformSuffix: String = ""): Dependency = {
    assert(dep.cross.isConstant, s"Not a Java dependency: $dep")
    depToDependency(dep, "", platformSuffix)
  }

  def depToDependency(dep: Dep, scalaVersion: String, platformSuffix: String = ""): Dependency =
    dep.toDependency(
      binaryVersion = scalaBinaryVersion(scalaVersion),
      fullVersion = scalaVersion,
      platformSuffix = platformSuffix
    )

  def depToBoundDep(dep: Dep, scalaVersion: String, platformSuffix: String = ""): BoundDep =
    BoundDep(depToDependency(dep, scalaVersion, platformSuffix), dep.force)

  def resolveDependenciesMetadataSafe(
      repositories: Seq[Repository],
      deps: IterableOnce[BoundDep],
      mapDependencies: Option[Dependency => Dependency] = None,
      customizer: Option[coursier.core.Resolution => coursier.core.Resolution] = None,
      ctx: Option[TaskCtx] = None,
      coursierCacheCustomizer: Option[
        coursier.cache.FileCache[Task] => coursier.cache.FileCache[Task]
      ] = None,
      resolutionParams: ResolutionParams = ResolutionParams(),
      boms: IterableOnce[BomDependency] = Nil,
      checkGradleModules: Boolean = false,
      @unroll config: mill.util.CoursierConfig = mill.util.CoursierConfig.default()
  ): Result[Resolution] = {
    val depSeq = deps.iterator.toSeq
    mill.util.Jvm.resolveDependenciesMetadataSafe(
      repositories = repositories,
      deps = depSeq.map(_.dep),
      force = depSeq.filter(_.force).map(_.dep),
      mapDependencies = mapDependencies,
      customizer = customizer,
      ctx = ctx,
      coursierCacheCustomizer = coursierCacheCustomizer,
      resolutionParams = resolutionParams,
      boms = boms,
      checkGradleModules = checkGradleModules,
      config = config
    )
  }

  /**
   * Resolve dependencies using Coursier.
   *
   * We do not bother breaking this out into the separate JvmWorker classpath,
   * because Coursier is already bundled with mill/Ammonite to support the
   * `//| mvnDeps` syntax.
   */
  def resolveDependencies(
      repositories: Seq[Repository],
      deps: IterableOnce[BoundDep],
      sources: Boolean = false,
      mapDependencies: Option[Dependency => Dependency] = None,
      customizer: Option[coursier.core.Resolution => coursier.core.Resolution] = None,
      ctx: Option[TaskCtx] = None,
      coursierCacheCustomizer: Option[
        coursier.cache.FileCache[Task] => coursier.cache.FileCache[Task]
      ] = None,
      artifactTypes: Option[Set[Type]] = None,
      resolutionParams: ResolutionParams = ResolutionParams(),
      checkGradleModules: Boolean = false,
      @unroll config: mill.util.CoursierConfig = mill.util.CoursierConfig.default(),
      @unroll boms: IterableOnce[BomDependency] = Nil
  ): Result[Seq[PathRef]] = {
    val depSeq = deps.iterator.toSeq
    val res = mill.util.Jvm.resolveDependencies(
      repositories = repositories,
      deps = depSeq.map(_.dep),
      force = depSeq.filter(_.force).map(_.dep),
      sources = sources,
      artifactTypes = artifactTypes,
      mapDependencies = mapDependencies,
      customizer = customizer,
      ctx = ctx,
      coursierCacheCustomizer = coursierCacheCustomizer,
      resolutionParams = resolutionParams,
      checkGradleModules = checkGradleModules,
      config = config,
      boms = boms
    )

    res.map(_.map(_.withRevalidateOnce))
  }

  /** Artifact names for the Scala compiler (without library) */
  def scalaCompilerArtifacts(scalaVersion: String): Seq[String] =
    if (isDotty(scalaVersion)) Seq("dotty-compiler")
    else if (isScala3(scalaVersion)) Seq("scala3-compiler")
    else Seq("scala-compiler", "scala-reflect")

  /** Artifact name for the Scala runtime library */
  def scalaLibraryArtifact(scalaVersion: String): String =
    if (isDotty(scalaVersion)) "dotty-library"
    else if (usesScala3Library(scalaVersion)) "scala3-library"
    else "scala-library"

  /** All Scala artifact names (compiler + library) */
  def scalaArtifacts(scalaVersion: String): Set[String] =
    scalaCompilerArtifacts(scalaVersion).toSet + scalaLibraryArtifact(scalaVersion)

  def scalaCompilerMvnDeps(scalaOrg: String, scalaVersion: String): Seq[Dep] =
    scalaCompilerArtifacts(scalaVersion).map { a =>
      // Dotty/Scala 3 use :: (cross-version with _3 suffix),
      // Scala 2 uses : (no suffix)
      if (isDottyOrScala3(scalaVersion)) Dep.parse(s"$scalaOrg::$a:$scalaVersion")
      else Dep.parse(s"$scalaOrg:$a:$scalaVersion")
    }

  def scalaConsoleMvnDeps(scalaOrg: String, scalaVersion: String): Seq[Dep] =
    // Since Scala 3.8, the repl is no longer part of the compiler jar
    if (usesScalaLibraryOnly(scalaVersion)) Seq(mvn"$scalaOrg::scala3-repl:$scalaVersion")
    else scalaCompilerMvnDeps(scalaOrg, scalaVersion)

  def scalaDocMvnDeps(scalaOrg: String, scalaVersion: String): Seq[Dep] =
    if (isDotty(scalaVersion)) Seq(mvn"$scalaOrg::dotty-doc:$scalaVersion")
    // 3.0.0-RC1 > scalaVersion >= 3.0.0-M1 still uses dotty-doc, but under a different artifact name
    else if (isScala3Milestone(scalaVersion)) Seq(mvn"$scalaOrg::scala3-doc:$scalaVersion")
    // scalaVersion >= 3.0.0-RC1 uses scaladoc
    else if (isScala3(scalaVersion)) Seq(mvn"$scalaOrg::scaladoc:$scalaVersion")
    // in Scala <= 2.13, the scaladoc tool is included in the compiler
    else scalaCompilerMvnDeps(scalaOrg, scalaVersion)

  def scalaRuntimeMvnDeps(scalaOrg: String, scalaVersion: String): Seq[Dep] = {
    val artifact = scalaLibraryArtifact(scalaVersion)
    // Dotty/Scala 3 pre-3.8 use :: (cross-version with _3 suffix),
    // Scala 2 and Scala 3.8+ use : (no suffix, published as plain scala-library)
    if (usesScala3Library(scalaVersion) || isDotty(scalaVersion))
      Seq(Dep.parse(s"$scalaOrg::$artifact:$scalaVersion"))
    else Seq(Dep.parse(s"$scalaOrg:$artifact:$scalaVersion"))
  }

  def findSourceFiles(sources: Seq[PathRef], extensions: Seq[String]): Seq[os.Path] = {
    def isHiddenFile(path: os.Path) = path.last.startsWith(".")
    for {
      root <- sources
      if os.exists(root.path)
      path <- (if (os.isDir(root.path)) os.walk(root.path) else Seq(root.path))
      if os.isFile(path) &&
        extensions.exists(ex => path.last.endsWith(s".$ex")) &&
        !isHiddenFile(path)
    } yield path
  }

}
