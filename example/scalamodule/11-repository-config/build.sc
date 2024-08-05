// By default, dependencies are resolved from maven central, but you can add
// your own resolvers by overriding the `repositoriesTask` task in the module:

//// SNIPPET:BUILD1

import mill._, scalalib._
import mill.define.ModuleRef
import coursier.maven.MavenRepository

val sonatypeReleases = Seq(
  MavenRepository("https://oss.sonatype.org/content/repositories/releases")
)

object foo extends ScalaModule {
  def scalaVersion = "2.13.8"

  def ivyDeps = Agg(
    ivy"com.lihaoyi::scalatags:0.12.0",
    ivy"com.lihaoyi::mainargs:0.6.2"
  )

  def repositoriesTask = T.task {
    super.repositoriesTask() ++ sonatypeReleases
  }
}

//// SNIPPET:END

// Mill read https://get-coursier.io/[coursier] config files automatically.
//
// It is possible to setup mirror with `mirror.properties`
// [source,properties]
// ----
// central.from=https://repo1.maven.org/maven2
// central.to=http://example.com:8080/nexus/content/groups/public
// ----
//
// Note theses default config file locatations:
//
// * Linux: `~/.config/coursier/mirror.properties`
// * MacOS: `~/Library/Preferences/Coursier/mirror.properties`
// * Windows: `C:\Users\<user_name>\AppData\Roaming\Coursier\config\mirror.properties`
//
// You can also set the environment variable `COURSIER_MIRRORS` or the jvm property `coursier.mirrors` to specify config file location.


// To add custom resolvers to the initial bootstrap of the build, you can create a
// custom `ZincWorkerModule`, and override the `zincWorker` method in your
// `ScalaModule` by pointing it to that custom object:

//// SNIPPET:BUILD2

object CustomZincWorkerModule extends ZincWorkerModule with CoursierModule {
  def repositoriesTask = T.task { super.repositoriesTask() ++ sonatypeReleases }
}

object bar extends ScalaModule {
  def scalaVersion = "2.13.8"
  def zincWorker = ModuleRef(CustomZincWorkerModule)
  // ... rest of your build definitions

  def repositoriesTask = T.task {super.repositoriesTask() ++ sonatypeReleases}
}

//// SNIPPET:END

/** Usage

> ./mill foo.run --text hello

> ./mill bar.compile

*/
