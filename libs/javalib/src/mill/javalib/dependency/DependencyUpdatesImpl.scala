package mill.javalib.dependency

import com.lihaoyi.unroll
import mill.api.*
import mill.api.internal.RootModule0
import mill.javalib.CoursierConfigModule
import mill.javalib.dependency.updates.{DependencyUpdates, ModuleDependenciesUpdates, UpdatesFinder}
import mill.javalib.dependency.versions.{ModuleDependenciesVersions, VersionsFinder}

object DependencyUpdatesImpl {

  def apply(
      evaluator: Evaluator,
      ctx: TaskCtx,
      rootModule: RootModule0,
      allowPreRelease: Boolean,
      @unroll coursierConfigModule: CoursierConfigModule = CoursierConfigModule
  ): Seq[ModuleDependenciesUpdates] = {
    // 1. Find all available versions for each dependency
    val allDependencyVersions: Seq[ModuleDependenciesVersions] =
      VersionsFinder.findVersions(evaluator, ctx, rootModule, coursierConfigModule)

    // 2. Extract updated versions from all available versions
    val allUpdates = allDependencyVersions.map { dependencyVersions =>
      UpdatesFinder.findUpdates(dependencyVersions, allowPreRelease)
    }

    // 3. Return the results
    allUpdates
  }

  @deprecated("Use apply without `discover` instead", "1.0.3")
  def apply(
      evaluator: Evaluator,
      ctx: TaskCtx,
      rootModule: RootModule0,
      discover: Discover,
      allowPreRelease: Boolean
  ): Seq[ModuleDependenciesUpdates] = {
    val _ = discover // unused but part of public API
    apply(evaluator, ctx, rootModule, allowPreRelease)
  }

  def showAllUpdates(
      updates: Seq[ModuleDependenciesUpdates],
      format: Format = Format.PerModule
  ): Unit = {
    val theUpdates =
      updates.map(u => if (u.modulePath.isEmpty) u.copy(modulePath = "root module") else u)

    format match {
      case Format.PerModule =>
        theUpdates.foreach { dependencyUpdates =>
          val module = dependencyUpdates.modulePath
          val actualUpdates =
            dependencyUpdates.dependencies.filter(_.updates.nonEmpty)
          if (actualUpdates.isEmpty) {
            println(s"No dependency updates found for $module")
          } else {
            println(s"Found ${actualUpdates.length} dependency update for $module")
            showUpdates(actualUpdates)
          }
        }
      case Format.PerDependency =>
        val actualUpdates = theUpdates
          .view
          .flatMap(depUpdates =>
            depUpdates.dependencies
              .filter(_.updates.nonEmpty)
              .map(d => (formatDependencyUpdate(d), depUpdates.modulePath))
          )
          .groupBy(d => d._1)
          .map { u =>
            val dep = u._1 // formatDependencyUpdate(u._1)
            val modules = u._2.map(_._2).mkString("\n  ", "\n  ", "")
            s"${dep} in${modules}"
          }
          .toSeq

        if (actualUpdates.isEmpty) {
          println("No dependency updates found")
        } else {
          actualUpdates.sorted.foreach(println(_))
        }

    }
  }

  private def formatDependencyUpdate(dependencyUpdate: DependencyUpdates): String = {
    val module = s"${dependencyUpdate.dependency.module}"
    val versions = (dependencyUpdate.currentVersion +: dependencyUpdate.updates.toList)
      .mkString(" -> ")
    s"${module} : ${versions}"
  }

  private def showUpdates(updates: Seq[DependencyUpdates]): Unit =
    updates.foreach { dependencyUpdate =>
      println(s"  ${formatDependencyUpdate(dependencyUpdate)}")
    }
}
