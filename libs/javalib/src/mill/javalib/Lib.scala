package mill
package javalib

import com.lihaoyi.unroll
import coursier.core.BomDependency
import coursier.params.ResolutionParams
import coursier.util.Task
import coursier.{Dependency, Repository, Resolution, Type}
import mill.api.{TaskCtx, PathRef}
import mill.api.Result
import mill.javalib.api.JvmWorkerUtil

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
      binaryVersion = JvmWorkerUtil.scalaBinaryVersion(scalaVersion),
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
      @unroll config: mill.util.CoursierConfig = mill.util.CoursierConfig.default()
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
      config = config
    )

    res.map(_.map(_.withRevalidateOnce))
  }

  def scalaCompilerMvnDeps(scalaOrganization: String, scalaVersion: String): Seq[Dep] =
    if (JvmWorkerUtil.isDotty(scalaVersion))
      Seq(mvn"$scalaOrganization::dotty-compiler:$scalaVersion")
    else if (JvmWorkerUtil.isScala3(scalaVersion))
      Seq(mvn"$scalaOrganization::scala3-compiler:$scalaVersion")
    else
      Seq(
        mvn"$scalaOrganization:scala-compiler:$scalaVersion",
        mvn"$scalaOrganization:scala-reflect:$scalaVersion"
      )

  def scalaDocMvnDeps(scalaOrganization: String, scalaVersion: String): Seq[Dep] =
    if (JvmWorkerUtil.isDotty(scalaVersion))
      Seq(mvn"$scalaOrganization::dotty-doc:$scalaVersion")
    else if (JvmWorkerUtil.isScala3Milestone(scalaVersion))
      // 3.0.0-RC1 > scalaVersion >= 3.0.0-M1 still uses dotty-doc, but under a different artifact name
      Seq(mvn"$scalaOrganization::scala3-doc:$scalaVersion")
    else if (JvmWorkerUtil.isScala3(scalaVersion))
      // scalaVersion >= 3.0.0-RC1 uses scaladoc
      Seq(mvn"$scalaOrganization::scaladoc:$scalaVersion")
    else
      // in Scala <= 2.13, the scaladoc tool is included in the compiler
      scalaCompilerMvnDeps(scalaOrganization, scalaVersion)

  def scalaRuntimeMvnDeps(scalaOrganization: String, scalaVersion: String): Seq[Dep] =
    if (JvmWorkerUtil.isDotty(scalaVersion)) {
      Seq(mvn"$scalaOrganization::dotty-library:$scalaVersion")
    } else if (JvmWorkerUtil.isScala3(scalaVersion))
      Seq(mvn"$scalaOrganization::scala3-library:$scalaVersion")
    else
      Seq(mvn"$scalaOrganization:scala-library:$scalaVersion")

  def findSourceFiles(sources: Seq[PathRef], extensions: Seq[String]): Seq[os.Path] = {
    def isHiddenFile(path: os.Path) = path.last.startsWith(".")
    for {
      root <- sources
      if os.exists(root.path)
      path <- (if (os.isDir(root.path)) os.walk(root.path) else Seq(root.path))
      if os.isFile(path) && (extensions.exists(ex => path.last.endsWith(s".$ex")) && !isHiddenFile(
        path
      ))
    } yield path
  }

}
