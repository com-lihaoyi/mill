package mill.scalalib.dependency

import mill.define._
import mill.scalalib.dependency.versions.VersionsFinder
import mill.util.Ctx.{Home, Log}

object DependencyUpdatesImpl {

  def apply(ctx: Log with Home,
            rootModule: BaseModule,
            discover: Discover[_]): Unit = {
    println(s"Dependency updates")

    val allDependencyVersions = VersionsFinder.findVersions(ctx, rootModule)

    allDependencyVersions.foreach { dependencyVersions =>
      println("----------")
      println(dependencyVersions.module)
      println("----------")
      dependencyVersions.dependencies.foreach { dependencyVersion =>
        println(
          s"${dependencyVersion.dependency.module} (${dependencyVersion.currentVersion})")
        println(dependencyVersion.allversions)
        println()
      }
      println()
    }

  }
}
