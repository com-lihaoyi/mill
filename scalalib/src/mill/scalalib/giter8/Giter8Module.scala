package mill.scalalib.giter8

import mill.T
import mill.define.{Command, Discover, ExternalModule}
import mill.util.Jvm
import mill.scalalib.api.ZincWorkerUtil
import mill.scalalib._
import mill.BuildInfo
import mill.api.Loose

object Giter8Module extends ExternalModule with Giter8Module {
  lazy val millDiscover = Discover[this.type]
}

trait Giter8Module extends CoursierModule {

  def init(args: String*): Command[Unit] = T.command {
    T.log.info("Creating a new project...")
    val giter8Dependencies = resolveDeps(
      T.task {
        val scalaBinVersion = ZincWorkerUtil.scalaBinaryVersion(BuildInfo.scalaVersion)
        Loose.Agg(ivy"org.foundweekends.giter8:giter8_${scalaBinVersion}:0.14.0"
          .bindDep("", "", ""))
      }
    )()

    Jvm.runSubprocess(
      "giter8.Giter8",
      giter8Dependencies.map(_.path),
      mainArgs = args,
      workingDir = T.workspace
    )
  }
}
