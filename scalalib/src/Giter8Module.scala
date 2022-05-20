package mill
package scalalib

import scala.util.control.NonFatal

import mill.T
import mill.define.{Command, Discover, ExternalModule}
import mill.modules.Jvm
import mill.define.Task

object Giter8Module extends ExternalModule with CoursierModule {

  override def resolveCoursierDependency: Task[Dep => coursier.Dependency] =
    T.task { (dep: Dep) =>
      val scalaVersion = scala.util.Properties.releaseVersion.getOrElse("2.13.8")
      Lib.depToDependency(dep, scalaVersion)
    }

  def init(args: String*): Command[Unit] = T.command {
    T.log.info("Creating a new project...")

    val giter8Dependencies = resolveDeps(
      T.task {
        Agg(ivy"org.foundweekends.giter8::giter8:0.14.0")
      }
    )()

    Jvm.runLocal(
      "giter8.Giter8",
      giter8Dependencies.map(_.path),
      args
    )
  }

  lazy val millDiscover = Discover[this.type]

}
