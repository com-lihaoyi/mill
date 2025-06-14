package mill.scalalib.giter8

import mill.Task
import mill.define.{Discover, ExternalModule}
import mill.util.Jvm
import mill.scalalib.api.JvmWorkerUtil
import mill.scalalib._
import mill.util.BuildInfo
import mill.define.BuildCtx

object Giter8Module extends ExternalModule with Giter8Module {
  lazy val millDiscover = Discover[this.type]
}

trait Giter8Module extends CoursierModule {

  def init(args: String*): Task.Command[Unit] = Task.Command {
    Task.log.info("Creating a new project...")

    val giter8Dependencies =
      try {
        defaultResolver().classpath {
          val scalaBinVersion = {
            val bv = JvmWorkerUtil.scalaBinaryVersion(BuildInfo.scalaVersion)
            if (bv == "3") "2.13" else bv
          }
          Seq(mvn"org.foundweekends.giter8:giter8_${scalaBinVersion}:0.14.0"
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
      cwd = BuildCtx.workspaceRoot,
      stdin = os.Inherit,
      stdout = os.Inherit
    )
  }
}
