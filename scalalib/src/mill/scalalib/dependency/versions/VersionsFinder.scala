package mill.scalalib.dependency.versions

import scala.reflect.ClassTag

import coursier.Dependency
import mill.define.{BaseModule, Task}
import mill.eval.Evaluator
import mill.scalalib.dependency.metadata.MetadataLoaderFactory
import mill.scalalib.{Dep, JavaModule, Lib}
import mill.api.Ctx.{Home, Log}
import mill.T

private[dependency] object VersionsFinder {

  def findVersions(
      evaluator: Evaluator,
      ctx: Log with Home,
      rootModule: BaseModule
  ): Seq[ModuleDependenciesVersions] = {

    val javaModules = rootModule.millInternal.modules.collect {
      case javaModule: JavaModule => javaModule
    }

    val resolvedDependencies = resolveDependencies(evaluator, javaModules)
    resolveVersions(evaluator, resolvedDependencies)
  }

  private def resolveDependencies(
      evaluator: Evaluator,
      javaModules: Seq[JavaModule]
  ): Seq[(JavaModule, Seq[Dependency])] = Evaluator.evalOrThrow(evaluator) {
    javaModules.map { javaModule =>
      T.task {
        val bindDependency = javaModule.bindDependency()
        val deps = javaModule.ivyDeps()
        val compileIvyDeps = javaModule.compileIvyDeps()
        val runIvyDeps = javaModule.runIvyDeps()
        val repos = javaModule.repositoriesTask()
        val mapDeps = javaModule.mapDependencies()
        val custom = javaModule.resolutionCustomizer()
        val cacheCustom = javaModule.coursierCacheCustomizer()

        val (dependencies, _) =
          Lib.resolveDependenciesMetadata(
            repositories = repos,
            deps = (deps ++ compileIvyDeps ++ runIvyDeps).map(bindDependency),
            mapDependencies = Some(mapDeps),
            customizer = custom,
            coursierCacheCustomizer = cacheCustom,
            ctx = Some(T.log)
          )

        (javaModule, dependencies)
      }
    }
  }

  private def resolveVersions(
      evaluator: Evaluator,
      resolvedDependencies: Seq[ResolvedDependencies]
  ): Seq[ModuleDependenciesVersions] =
    resolvedDependencies.map {
      case (javaModule, dependencies) =>
        val metadataLoaders =
          Evaluator.evalOrThrow(evaluator)(javaModule.repositoriesTask)
            .flatMap(MetadataLoaderFactory(_))

        val versions = dependencies.map { dependency =>
          val currentVersion = Version(dependency.version)
          val allVersions =
            metadataLoaders
              .flatMap(_.getVersions(dependency.module))
              .toSet
          DependencyVersions(dependency, currentVersion, allVersions)
        }

        ModuleDependenciesVersions(javaModule.toString, versions)
    }

  private type ResolvedDependencies = (JavaModule, Seq[coursier.Dependency])
}
