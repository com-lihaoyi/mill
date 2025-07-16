package mill.androidlib.keytool

import coursier.Repository
import mill.androidlib.AndroidSdkModule
import mill.api.{Discover, ExternalModule, PathRef, Task}
import mill.javalib.{Dep, JvmWorkerModule}
import mill.{T, Task}
import scala.concurrent.duration.FiniteDuration


@mill.api.experimental
trait KeytoolModule extends ExternalModule, JvmWorkerModule {
  override def repositoriesTask: Task[Seq[Repository]] = Task.Anon {
    super.repositoriesTask() :+ AndroidSdkModule.mavenGoogle
  }

  def toolsClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(
        Dep.millProjectModule("mill-libs-androidlib-keytool")
      )
    )
  }

  def createKeystoreWithCertificate(
      keystorePath: os.Path,
      keystorePass: String,
      alias: String,
      keyPass: String,
      dname: String,
      validity: FiniteDuration = FiniteDuration(365, "DAYS")) = Task.Anon {
    val mainClass = "mill.androidlib.keytool.Keytool"
    mill.util.Jvm.callProcess(
      mainClass = mainClass,
      classPath = toolsClasspath().map(_.path),
      mainArgs = Seq(
        "--keystore",
        keystorePath.toString,
        "--alias",
        alias,
        "--keypass",
        keyPass,
        "--storepass",
        keystorePass,
        "--dname",
        dname,
        "--validity-days",
        validity.toDays.toString
      )
    )
  }

  override lazy val millDiscover: Discover = Discover[this.type]
}

/** Provide the actual module instance for Mill */
object KeytoolModule extends KeytoolModule
