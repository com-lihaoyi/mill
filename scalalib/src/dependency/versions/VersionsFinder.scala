package mill.scalalib.dependency.versions

import coursier.core.Repository
import mill.define.{BaseModule, Task}
import mill.eval.Evaluator
import mill.scalalib.dependency.metadata.MetadataLoaderFactory
import mill.scalalib.{Dep, JavaModule, Lib}
import mill.api.Ctx.{Home, Log}
import mill.api.{Loose, Strict}

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

  private def resolveDependencies(evaluator: Evaluator, javaModules: Seq[JavaModule]) =
    javaModules.map { javaModule =>
      val depToDependency = eval(evaluator, javaModule.resolveCoursierDependency)
      val deps = evalOrElse(evaluator, javaModule.ivyDeps, Loose.Agg.empty[Dep])
      val compileIvyDeps = evalOrElse(evaluator, javaModule.compileIvyDeps, Loose.Agg.empty[Dep])
      val runIvyDeps = evalOrElse(evaluator, javaModule.runIvyDeps, Loose.Agg.empty[Dep])
      val repos = evalOrElse(evaluator, javaModule.repositoriesTask, Seq.empty[Repository])
      val mapDeps = evalOrElse(evaluator, javaModule.mapDependencies, (d: coursier.Dependency) => d)
      val custom = evalOrElse(evaluator, javaModule.resolutionCustomizer, None)

      val (dependencies, _) =
        Lib.resolveDependenciesMetadata(
          repositories = repos,
          depToDependency = depToDependency,
          deps = deps ++ compileIvyDeps ++ runIvyDeps,
          mapDependencies = Some(mapDeps),
          customizer = custom
        )

      (javaModule, dependencies)
    }

  private def resolveVersions(
      evaluator: Evaluator,
      resolvedDependencies: Seq[ResolvedDependencies]
  ) =
    resolvedDependencies.map {
      case (javaModule, dependencies) =>
        val metadataLoaders =
          evalOrElse(evaluator, javaModule.repositoriesTask, Seq.empty[Repository])
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

  private def eval[T](evaluator: Evaluator, e: Task[T]): T =
    evaluator.evaluate(Strict.Agg(e)).values match {
      case Seq() => throw new NoSuchElementException
      case Seq(e: T) => e
    }

  private def evalOrElse[T](evaluator: Evaluator, e: Task[T], default: => T): T =
    evaluator.evaluate(Strict.Agg(e)).values match {
      case Seq() => default
      case Seq(e: T) => e
    }

  private type ResolvedDependencies = (JavaModule, Seq[coursier.Dependency])
}
