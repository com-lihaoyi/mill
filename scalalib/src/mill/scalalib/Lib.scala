package mill
package scalalib

import coursier.util.Task
import coursier.{Dependency, Repository, Resolution}
import mill.api.{Ctx, Loose, PathRef, Result}
import mill.scalalib.api.ZincWorkerUtil

object Lib {
  def depToDependencyJava(dep: Dep, platformSuffix: String = ""): Dependency = {
    assert(dep.cross.isConstant, s"Not a Java dependency: $dep")
    depToDependency(dep, "", platformSuffix)
  }

  def depToDependency(dep: Dep, scalaVersion: String, platformSuffix: String = ""): Dependency =
    dep.toDependency(
      binaryVersion = ZincWorkerUtil.scalaBinaryVersion(scalaVersion),
      fullVersion = scalaVersion,
      platformSuffix = platformSuffix
    )

  def depToBoundDep(dep: Dep, scalaVersion: String, platformSuffix: String = ""): BoundDep =
    BoundDep(depToDependency(dep, scalaVersion, platformSuffix), dep.force)

  def resolveDependenciesMetadata(
      repositories: Seq[Repository],
      deps: IterableOnce[BoundDep],
      mapDependencies: Option[Dependency => Dependency] = None,
      customizer: Option[coursier.core.Resolution => coursier.core.Resolution] = None,
      ctx: Option[Ctx.Log] = None,
      coursierCacheCustomizer: Option[
        coursier.cache.FileCache[Task] => coursier.cache.FileCache[Task]
      ] = None
  ): (Seq[Dependency], Resolution) = {
    val depSeq = deps.iterator.toSeq
    mill.modules.Jvm.resolveDependenciesMetadata(
      repositories = repositories,
      deps = depSeq.map(_.dep),
      force = depSeq.filter(_.force).map(_.dep),
      mapDependencies = mapDependencies,
      customizer = customizer,
      ctx = ctx,
      coursierCacheCustomizer = coursierCacheCustomizer
    )
  }

  /**
   * Resolve dependencies using Coursier.
   *
   * We do not bother breaking this out into the separate ZincWorker classpath,
   * because Coursier is already bundled with mill/Ammonite to support the
   * `import $ivy` syntax.
   */
  def resolveDependencies(
      repositories: Seq[Repository],
      deps: IterableOnce[BoundDep],
      sources: Boolean = false,
      mapDependencies: Option[Dependency => Dependency] = None,
      customizer: Option[coursier.core.Resolution => coursier.core.Resolution] = None,
      ctx: Option[Ctx.Log] = None,
      coursierCacheCustomizer: Option[
        coursier.cache.FileCache[Task] => coursier.cache.FileCache[Task]
      ] = None
  ): Result[Agg[PathRef]] = {
    val depSeq = deps.iterator.toSeq
    mill.modules.Jvm.resolveDependencies(
      repositories = repositories,
      deps = depSeq.map(_.dep),
      force = depSeq.filter(_.force).map(_.dep),
      sources = sources,
      mapDependencies = mapDependencies,
      customizer = customizer,
      ctx = ctx,
      coursierCacheCustomizer = coursierCacheCustomizer
    )
  }

  def scalaCompilerIvyDeps(scalaOrganization: String, scalaVersion: String): Loose.Agg[Dep] =
    if (ZincWorkerUtil.isDotty(scalaVersion))
      Agg(
        ivy"$scalaOrganization::dotty-compiler:$scalaVersion".forceVersion()
      )
    else if (ZincWorkerUtil.isScala3(scalaVersion))
      Agg(
        ivy"$scalaOrganization::scala3-compiler:$scalaVersion".forceVersion()
      )
    else
      Agg(
        ivy"$scalaOrganization:scala-compiler:$scalaVersion".forceVersion(),
        ivy"$scalaOrganization:scala-reflect:$scalaVersion".forceVersion()
      )

  def scalaDocIvyDeps(scalaOrganization: String, scalaVersion: String): Loose.Agg[Dep] =
    if (ZincWorkerUtil.isDotty(scalaVersion))
      Agg(
        ivy"$scalaOrganization::dotty-doc:$scalaVersion".forceVersion()
      )
    else if (ZincWorkerUtil.isScala3Milestone(scalaVersion))
      Agg(
        // 3.0.0-RC1 > scalaVersion >= 3.0.0-M1 still uses dotty-doc, but under a different artifact name
        ivy"$scalaOrganization::scala3-doc:$scalaVersion".forceVersion()
      )
    else if (ZincWorkerUtil.isScala3(scalaVersion))
      Agg(
        // scalaVersion >= 3.0.0-RC1 uses scaladoc
        ivy"$scalaOrganization::scaladoc:$scalaVersion".forceVersion()
      )
    else
      // in Scala <= 2.13, the scaladoc tool is included in the compiler
      scalaCompilerIvyDeps(scalaOrganization, scalaVersion)

  def scalaRuntimeIvyDeps(scalaOrganization: String, scalaVersion: String): Loose.Agg[Dep] =
    if (ZincWorkerUtil.isDotty(scalaVersion)) {
      Agg(
        // note that dotty-library has a binary version suffix, hence the :: is necessary here
        ivy"$scalaOrganization::dotty-library:$scalaVersion".forceVersion()
      )
    } else if (ZincWorkerUtil.isScala3(scalaVersion))
      Agg(
        // note that dotty-library has a binary version suffix, hence the :: is necessary here
        ivy"$scalaOrganization::scala3-library::$scalaVersion".forceVersion()
      )
    else
      Agg(
        ivy"$scalaOrganization:scala-library:$scalaVersion".forceVersion()
      )

  def findSourceFiles(sources: Seq[PathRef], extensions: Seq[String]): Seq[os.Path] = {
    def isHiddenFile(path: os.Path) = path.last.startsWith(".")
    for {
      root <- sources
      if os.exists(root.path)
      path <- (if (os.isDir(root.path)) os.walk(root.path) else Seq(root.path))
      if os.isFile(path) && (extensions.exists(path.ext == _) && !isHiddenFile(path))
    } yield path
  }

}
