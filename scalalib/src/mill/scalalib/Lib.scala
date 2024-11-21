package mill
package scalalib

import coursier.params.ResolutionParams
import coursier.util.Task
import coursier.{Dependency, Repository, Resolution, Type}
import mill.api.{Ctx, Loose, PathRef, Result}
import mill.main.BuildInfo
import mill.main.client.EnvVars
import mill.util.Util
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

  @deprecated(
    "Prefer resolveDependenciesMetadataSafe instead, which returns a Result instead of throwing exceptions",
    "0.12.0"
  )
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
    val deps0 = deps.iterator.toSeq
    val res = resolveDependenciesMetadataSafe(
      repositories,
      deps0,
      mapDependencies,
      customizer,
      ctx,
      coursierCacheCustomizer
    )
    (deps0.map(_.dep), res.getOrThrow)
  }

  def resolveDependenciesMetadataSafe(
      repositories: Seq[Repository],
      deps: IterableOnce[BoundDep],
      mapDependencies: Option[Dependency => Dependency] = None,
      customizer: Option[coursier.core.Resolution => coursier.core.Resolution] = None,
      ctx: Option[Ctx.Log] = None,
      coursierCacheCustomizer: Option[
        coursier.cache.FileCache[Task] => coursier.cache.FileCache[Task]
      ] = None,
      resolutionParams: ResolutionParams = ResolutionParams(),
      bomDeps: IterableOnce[(coursier.core.Module, String)] = Nil
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
      bomDeps = bomDeps
    )
  }

  // bin-compat shim
  def resolveDependenciesMetadataSafe(
      repositories: Seq[Repository],
      deps: IterableOnce[BoundDep],
      mapDependencies: Option[Dependency => Dependency],
      customizer: Option[coursier.core.Resolution => coursier.core.Resolution],
      ctx: Option[Ctx.Log],
      coursierCacheCustomizer: Option[
        coursier.cache.FileCache[Task] => coursier.cache.FileCache[Task]
      ]
  ): Result[Resolution] =
    resolveDependenciesMetadataSafe(
      repositories,
      deps,
      mapDependencies,
      customizer,
      ctx,
      coursierCacheCustomizer,
      ResolutionParams()
    )

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
      ] = None,
      artifactTypes: Option[Set[Type]] = None,
      resolutionParams: ResolutionParams = ResolutionParams(),
      bomDeps: IterableOnce[(coursier.core.Module, String)] = Nil
  ): Result[Agg[PathRef]] = {
    val depSeq = deps.iterator.toSeq
    mill.util.Jvm.resolveDependencies(
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
      bomDeps = bomDeps
    ).map(_.map(_.withRevalidateOnce))
  }

  // bin-compat shim
  def resolveDependencies(
      repositories: Seq[Repository],
      deps: IterableOnce[BoundDep],
      sources: Boolean,
      mapDependencies: Option[Dependency => Dependency],
      customizer: Option[coursier.core.Resolution => coursier.core.Resolution],
      ctx: Option[Ctx.Log],
      coursierCacheCustomizer: Option[
        coursier.cache.FileCache[Task] => coursier.cache.FileCache[Task]
      ],
      artifactTypes: Option[Set[Type]]
  ): Result[Agg[PathRef]] =
    resolveDependencies(
      repositories,
      deps,
      sources,
      mapDependencies,
      customizer,
      ctx,
      coursierCacheCustomizer,
      artifactTypes,
      ResolutionParams()
    )

  @deprecated("Use the override accepting artifactTypes", "Mill after 0.12.0-RC3")
  def resolveDependencies(
      repositories: Seq[Repository],
      deps: IterableOnce[BoundDep],
      sources: Boolean,
      mapDependencies: Option[Dependency => Dependency],
      customizer: Option[coursier.core.Resolution => coursier.core.Resolution],
      ctx: Option[Ctx.Log],
      coursierCacheCustomizer: Option[
        coursier.cache.FileCache[Task] => coursier.cache.FileCache[Task]
      ]
  ): Result[Agg[PathRef]] =
    resolveDependencies(
      repositories,
      deps,
      sources,
      mapDependencies,
      customizer,
      ctx,
      coursierCacheCustomizer,
      None,
      ResolutionParams(),
      Nil
    )

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
        ivy"$scalaOrganization::dotty-library:$scalaVersion".forceVersion()
      )
    } else if (ZincWorkerUtil.isScala3(scalaVersion))
      Agg(
        ivy"$scalaOrganization::scala3-library:$scalaVersion".forceVersion()
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
      if os.isFile(path) && (extensions.exists(ex => path.last.endsWith(s".$ex")) && !isHiddenFile(
        path
      ))
    } yield path
  }

  private[mill] def millAssemblyEmbeddedDeps: Agg[BoundDep] = Agg.from(
    BuildInfo.millEmbeddedDeps
      .split(",")
      .map(d => ivy"$d")
      .map(dep => Lib.depToBoundDep(dep, BuildInfo.scalaVersion))
  )

  def resolveMillBuildDeps(
      repos: Seq[Repository],
      ctx: Option[mill.api.Ctx.Log],
      useSources: Boolean
  ): Seq[os.Path] = {
    Util.millProperty(EnvVars.MILL_BUILD_LIBRARIES) match {
      case Some(found) => found.split(',').map(os.Path(_)).distinct.toList
      case None =>
        millAssemblyEmbeddedDeps
        val Result.Success(res) = scalalib.Lib.resolveDependencies(
          repositories = repos.toList,
          deps = millAssemblyEmbeddedDeps,
          sources = useSources,
          mapDependencies = None,
          customizer = None,
          coursierCacheCustomizer = None,
          ctx = ctx
        )
        res.items.toList.map(_.path)
    }
  }

}
