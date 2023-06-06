// By default, dependencies are resolved from maven central, but you can add
// your own resolvers by overriding the `repositoriesTask` task in the module:

import mill._, scalalib._
import mill.define.ModuleRef
import coursier.maven.MavenRepository

val sonatypeReleases = Seq(
  MavenRepository("https://oss.sonatype.org/content/repositories/releases")
)

object foo extends ScalaModule {
  def scalaVersion = "2.13.8"

  def repositoriesTask = T.task {
    super.repositoriesTask() ++ sonatypeReleases
  }
}

// To add custom resolvers to the initial bootstrap of the build, you can create a
// custom `ZincWorkerModule`, and override the `zincWorker` method in your
// `ScalaModule` by pointing it to that custom object:

object CustomZincWorkerModule extends ZincWorkerModule with CoursierModule {
  def repositoriesTask() = T.task { super.repositoriesTask() ++ sonatypeReleases }
}

object bar extends ScalaModule {
  def scalaVersion = "2.13.8"
  def zincWorker = ModuleRef(CustomZincWorkerModule)
  // ... rest of your build definitions

  def repositoriesTask = T.task {super.repositoriesTask() ++ sonatypeReleases}
}

/** Usage

> ./mill bar.compile

*/