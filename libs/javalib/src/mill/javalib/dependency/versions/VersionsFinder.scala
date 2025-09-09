package mill.javalib.dependency.versions

import mill.api.{Evaluator, Task}
import mill.javalib.dependency.metadata.{MetadataLoader, MetadataLoaderFactory}
import mill.javalib.CoursierConfigModule
import mill.javalib.{BoundDep, JavaModule, Lib}
import mill.api.TaskCtx
import mill.api.internal.RootModule0

import java.time.{Clock, Instant, ZoneId}
import java.util.concurrent.atomic.AtomicInteger

private[dependency] object VersionsFinder {

  def findVersions(
      evaluator: Evaluator,
      ctx: TaskCtx,
      rootModule: RootModule0,
      coursierConfigModule: CoursierConfigModule
  ): Seq[ModuleDependenciesVersions] = {

    val javaModules = rootModule.moduleInternal.modules.collect {
      case javaModule: JavaModule => javaModule
    }

    // Using a fixed time clock, so that the TTL cut-off is the same for all version checks,
    // and we don't run into race conditions like one check assuming a file in cache is valid,
    // another one a fraction of a second later assuming it's invalid and proceeding to
    // re-downloading it and deleting the former one, making the first check crash
    // (see https://github.com/com-lihaoyi/mill/issues/3876).
    val clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())

    val resolvedDependencies = evaluator.execute {
      val progress = new Progress(javaModules.size)
      javaModules.map(classpath(progress, ctx.offline, clock, coursierConfigModule))
    }.values.get

    evaluator.execute {
      val progress = new Progress(resolvedDependencies.map(_._3.size).sum)
      resolvedDependencies.map(resolveVersions(progress))
    }.values.get
  }

  class Progress(val count: Int) {
    private val counter = new AtomicInteger(1)
    def next(): Int = counter.getAndIncrement()
  }

  private def classpath(
      progress: Progress,
      offline: Boolean,
      clock: Clock,
      coursierConfigModule: CoursierConfigModule
  )(
      javaModule: JavaModule
  ): Task[ResolvedDependencies] =
    Task.Anon {
      Task.log.ticker(
        s"Resolving dependencies [${progress.next()}/${progress.count}]: ${javaModule}"
      )

      val bindDependency = javaModule.bindDependency()
      val deps = javaModule.mvnDeps()
      val compileMvnDeps = javaModule.compileMvnDeps()
      val runMvnDeps = javaModule.runMvnDeps()
      val repos = javaModule.repositoriesTask()
      val mapDeps = javaModule.mapDependencies()
      val custom = javaModule.resolutionCustomizer()
      val cacheCustom = javaModule.coursierCacheCustomizer()

      val metadataLoaders = repos.flatMap(MetadataLoaderFactory(_, offline, clock))

      val dependencies = (deps ++ compileMvnDeps ++ runMvnDeps)
        .map(bindDependency)
        .iterator
        .toSeq

      val x = Lib.resolveDependenciesMetadataSafe(
        repositories = repos,
        deps = dependencies: IterableOnce[BoundDep],
        mapDependencies = Option(mapDeps),
        customizer = custom,
        ctx = Option(Task.ctx()),
        coursierCacheCustomizer = cacheCustom,
        resolutionParams = coursier.params.ResolutionParams(),
        boms = Nil,
        checkGradleModules = javaModule.checkGradleModules(),
        config = coursierConfigModule.coursierConfig()
      )

      x.map { _ =>
        (javaModule, metadataLoaders, dependencies.map(_.dep))
      }
    }

  private def resolveVersions(progress: Progress)(
      resolvedDependencies: ResolvedDependencies
  ): Task[ModuleDependenciesVersions] = Task.Anon {
    val (javaModule, metadataLoaders, dependencies) = resolvedDependencies

    val versions = dependencies.map { dependency =>
      Task.log.ticker(
        s"Analyzing dependencies [${progress.next()}/${progress.count}]: ${javaModule} / ${dependency.module}"
      )
      val currentVersion = Version(dependency.versionConstraint.asString)
      val allVersions =
        metadataLoaders
          .flatMap(_.getVersions(dependency.module))
          .toSet
      DependencyVersions(dependency, currentVersion, allVersions)
    }

    ModuleDependenciesVersions(javaModule.toString, versions)
  }

  private type ResolvedDependencies = (JavaModule, Seq[MetadataLoader], Seq[coursier.Dependency])
}
