package mill.scalalib.giter8

import mill.{task, T}
import mill.define.{Command, Discover, ExternalModule}
import mill.util.Jvm
import mill.scalalib.api.ZincWorkerUtil
import mill.scalalib._
import mill.main.BuildInfo
import mill.api.Loose

object Giter8Module extends ExternalModule with Giter8Module {
  lazy val millDiscover: Discover[this.type] = Discover[this.type]
}

trait Giter8Module extends CoursierModule {

  def init(args: String*): Command[Unit] = task.command {
    task.log.info("Creating a new project...")
    val giter8Dependencies = defaultResolver().resolveDeps {
      val scalaBinVersion = ZincWorkerUtil.scalaBinaryVersion(BuildInfo.scalaVersion)
      Loose.Agg(ivy"org.foundweekends.giter8:giter8_${scalaBinVersion}:0.14.0"
        .bindDep("", "", ""))
    }

    Jvm.runSubprocess(
      "giter8.Giter8",
      giter8Dependencies.map(_.path),
      mainArgs = args,
      workingDir = task.workspace
    )
  }
}
