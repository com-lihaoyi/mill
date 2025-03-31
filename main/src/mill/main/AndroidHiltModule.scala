package mill.main

import coursier.LocalRepositories
import coursier.core.Repository
import coursier.maven.MavenRepository
import mill.api.PathRef
import mill.define.{Discover, ExternalModule, Target}
import mill.util.MillModuleUtil.millProjectModule

object AndroidHiltModule extends ExternalModule {
  def repositories: Seq[Repository] = Seq(
    LocalRepositories.ivy2Local,
    MavenRepository("https://repo1.maven.org/maven2"),
    MavenRepository("https://maven.google.com/")
  )

  lazy val millDiscover = Discover[this.type]

  def toolsClasspath: Target[Seq[PathRef]] = Target {
    millProjectModule("mill-main-androidhilt", repositories)
  }

}
