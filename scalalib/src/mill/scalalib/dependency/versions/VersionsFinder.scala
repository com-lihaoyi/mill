package mill.scalalib.dependency.versions

import mill.define.{BaseModule, Task}
import mill.eval.Evaluator
import mill.scalalib.dependency.metadata.{MetadataLoader, MetadataLoaderFactory}
import mill.scalalib.{JavaModule, Lib}
import mill.api.Ctx.{Home, Log}
import mill.T

import java.util.concurrent.atomic.AtomicInteger

private[dependency] object VersionsFinder {

  def findVersions(
      evaluator: Evaluator,
      ctx: Log with Home,
      rootModule: BaseModule
  ): Seq[ModuleDependenciesVersions] = {

    val javaModules = rootModule.millInternal.modules.collect {
      case javaModule: JavaModule => javaModule
    }

    val resolvedDependencies = evaluator.evalOrThrow() {
      val progress = new Progress(javaModules.size)
      javaModules.map(resolveDeps(progress))
    }

    evaluator.evalOrThrow() {
      val progress = new Progress(resolvedDependencies.map(_._3.size).sum)
      resolvedDependencies.map(resolveVersions(progress))
    }
  }

  class Progress(val count: Int) {
    private val counter = new AtomicInteger(1)
    def next(): Int = counter.getAndIncrement()
  }

  private def resolveDeps(progress: Progress)(
      javaModule: JavaModule
  ): Task[ResolvedDependencies] =
    Task.Anon {
      T.log.ticker(s"Resolving dependencies [${progress.next()}/${progress.count}]: ${javaModule}")

      val bindDependency = javaModule.bindDependency()
      val deps = javaModule.ivyDeps()
      val compileIvyDeps = javaModule.compileIvyDeps()
      val runIvyDeps = javaModule.runIvyDeps()
      val repos = javaModule.repositoriesTask()
      val mapDeps = javaModule.mapDependencies()
      val custom = javaModule.resolutionCustomizer()
      val cacheCustom = javaModule.coursierCacheCustomizer()

      val metadataLoaders = repos.flatMap(MetadataLoaderFactory(_))

      val dependencies = (deps ++ compileIvyDeps ++ runIvyDeps)
        .map(bindDependency)
        .iterator
        .toSeq
      val x = Lib.resolveDependenciesMetadataSafe(
        repositories = repos,
        deps = dependencies,
        mapDependencies = Option(mapDeps),
        customizer = custom,
        ctx = Some(T.log),
        coursierCacheCustomizer = cacheCustom,
        resolutionParams = coursier.params.ResolutionParams()
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
      T.log.ticker(
        s"Analyzing dependencies [${progress.next()}/${progress.count}]: ${javaModule} / ${dependency.module}"
      )
      val currentVersion = Version(dependency.version)
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
