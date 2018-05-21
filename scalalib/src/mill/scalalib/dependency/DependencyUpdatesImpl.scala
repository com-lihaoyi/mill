package mill.scalalib.dependency

import mill.define._
import mill.scalalib.dependency.updates.{
  DependencyUpdates,
  ModuleDependenciesUpdates,
  UpdatesFinder
}
import mill.scalalib.dependency.versions.VersionsFinder
import mill.util.Ctx.{Home, Log}

object DependencyUpdatesImpl {

  def apply(ctx: Log with Home,
            rootModule: BaseModule,
            discover: Discover[_],
            allowPreRelease: Boolean): Unit = {

    // 1. Find all available versions for each dependency
    val allDependencyVersions = VersionsFinder.findVersions(ctx, rootModule)

    // 2. Extract updated versions from all available versions
    val allUpdates = allDependencyVersions.map { dependencyVersions =>
      UpdatesFinder.findUpdates(dependencyVersions, allowPreRelease)
    }

    // 3. Print the results
    showAllUpdates(allUpdates)
  }

  private def showAllUpdates(updates: Seq[ModuleDependenciesUpdates]): Unit =
    updates.foreach { dependencyUpdates =>
      val module = dependencyUpdates.module.toString
      val actualUpdates =
        dependencyUpdates.dependencies.filter(_.updates.nonEmpty)
      if (actualUpdates.isEmpty) {
        println(s"No dependency updates found for $module")
      } else {
        println(s"Found ${actualUpdates.length} dependency update for $module")
        showUpdates(actualUpdates)
      }
    }

  private def showUpdates(updates: Seq[DependencyUpdates]): Unit =
    updates.foreach { dependencyUpdate =>
      val module = s"${dependencyUpdate.dependency.module}"
      val allVersions =
        (dependencyUpdate.currentVersion +: dependencyUpdate.updates.toList)
          .mkString(" -> ")
      println(s"  $module : $allVersions")
    }
}
