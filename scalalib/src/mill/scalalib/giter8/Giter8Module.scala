package mill.scalalib.giter8

import mill.Task
import mill.define.{Command, Discover, ExternalModule}
import mill.util.Jvm
import mill.scalalib.api.ZincWorkerUtil
import mill.scalalib._
import mill.main.BuildInfo
import mill.api.Loose

object Giter8Module extends ExternalModule with Giter8Module {
  lazy val millDiscover = Discover[this.type]
}

trait Giter8Module extends CoursierModule {

  def init(args: String*): Command[Unit] = Task.Command {
    Task.log.info("Creating a new project...")

    val giter8Dependencies =
      try {
        defaultResolver().resolveDeps {
          val scalaBinVersion = {
            val bv = ZincWorkerUtil.scalaBinaryVersion(BuildInfo.scalaVersion)
            if (bv == "3") "2.13" else bv
          }
          Loose.Agg(ivy"org.foundweekends.giter8:giter8_${scalaBinVersion}:0.14.0"
            .bindDep("", "", ""))
        }
      } catch {
        case e: Exception =>
          Task.log.error("Failed to resolve giter8 dependencies\n" + e.getMessage)
          throw e
      }

    Jvm.callProcess(
      mainClass = "giter8.Giter8",
      classPath = giter8Dependencies.map(_.path).toVector,
      mainArgs = args,
      cwd = Task.workspace,
      stdin = os.Inherit,
      stdout = os.Inherit
    )
  }
}
