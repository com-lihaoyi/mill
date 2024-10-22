package mill.scalalib.dependency

import mill.api.Ctx.{Home, Log}
import mill.define._
import mill.eval.Evaluator
import mill.scalalib.dependency.updates.{
  DependencyUpdates,
  ModuleDependenciesUpdates,
  UpdatesFinder
}
import mill.scalalib.dependency.versions.{ModuleDependenciesVersions, VersionsFinder}

object DependencyUpdatesImpl {

  def apply(
      evaluator: Evaluator,
      ctx: Log with Home,
      rootModule: BaseModule,
      discover: Discover,
      allowPreRelease: Boolean
  ): Seq[ModuleDependenciesUpdates] = {

    // 1. Find all available versions for each dependency
    val allDependencyVersions: Seq[ModuleDependenciesVersions] =
      VersionsFinder.findVersions(evaluator, ctx, rootModule)

    // 2. Extract updated versions from all available versions
    val allUpdates = allDependencyVersions.map { dependencyVersions =>
      UpdatesFinder.findUpdates(dependencyVersions, allowPreRelease)
    }

    // 3. Return the results
    allUpdates
  }

  @deprecated("Use other overload instead", "Mill after 0.11.6")
  def showAllUpdates(updates: Seq[ModuleDependenciesUpdates]): Unit =
    showAllUpdates(updates, format = Format.PerModule)

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
        val acutalUpdates = theUpdates
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
            s"${dep} in ${modules}"
          }
          .toSeq

        if (acutalUpdates.isEmpty) {
          println("No dependency updates found")
        } else {
          acutalUpdates.sorted.foreach(println(_))
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
