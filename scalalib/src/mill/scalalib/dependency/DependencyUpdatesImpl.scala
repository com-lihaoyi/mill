package mill.scalalib.dependency

import mill.define._
import mill.scalalib.dependency.updates.UpdatesFinder
import mill.scalalib.dependency.versions.VersionsFinder
import mill.util.Ctx.{Home, Log}

object DependencyUpdatesImpl {

  def apply(ctx: Log with Home,
            rootModule: BaseModule,
            discover: Discover[_]): Unit = {
    println(s"Dependency updates")

    val allDependencyVersions = VersionsFinder.findVersions(ctx, rootModule)

    val allUpdates = allDependencyVersions.map { dependencyVersions =>
      UpdatesFinder.findUpdates(dependencyVersions, allowPreRelease = false)
    }

    allUpdates.foreach { dependencyUpdates =>
      println("----------")
      println(dependencyUpdates.module)
      println("----------")
      dependencyUpdates.dependencies.foreach { dependencyUpdate =>
        println(
          s"${dependencyUpdate.dependency.module} (${dependencyUpdate.currentVersion})")
        println(dependencyUpdate.updates)
        println()
      }
      println()
    }
  }
}
