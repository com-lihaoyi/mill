//// SNIPPET:BUILD1

import mill._, javalib._
import mill.define.ModuleRef
import coursier.maven.MavenRepository

val sonatypeReleases = Seq(
  MavenRepository("https://oss.sonatype.org/content/repositories/releases")
)

object foo extends JavaModule {

  def ivyDeps = Agg(
    ivy"net.sourceforge.argparse4j:argparse4j:0.9.0",
    ivy"org.apache.commons:commons-text:1.12.0"
  )

  def repositoriesTask = T.task { super.repositoriesTask() ++ sonatypeReleases }
}

//// SNIPPET:BUILD2

object CustomZincWorkerModule extends ZincWorkerModule with CoursierModule {
  def repositoriesTask = T.task { super.repositoriesTask() ++ sonatypeReleases }
}

object bar extends JavaModule {
  def zincWorker = ModuleRef(CustomZincWorkerModule)
  // ... rest of your build definitions

  def repositoriesTask = T.task { super.repositoriesTask() ++ sonatypeReleases }
}
