package mill.androidlib.keytool

import coursier.Repository
import mill.androidlib.AndroidSdkModule
import mill.api.{Discover, ExternalModule, PathRef, Task}
import mill.javalib.{Dep, JvmWorkerModule}
import mill.{T, Task}
import mainargs.{ParserForMethods, arg, main}

@mill.api.experimental
trait KeytoolModule extends ExternalModule, JvmWorkerModule {
  override def repositoriesTask: Task[Seq[Repository]] = Task.Anon {
    super.repositoriesTask() :+ AndroidSdkModule.mavenGoogle
  }

  def classpath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(
        Dep.millProjectModule("mill-libs-androidlib-keytool")
      )
    )
  }

  def createKeystoreWithCertificate(
      args: Task[Seq[String]]
  ) = Task.Anon {
    val mainClass = "mill.androidlib.keytool.Keytool"
    val res = mill.util.Jvm.callProcess(
      mainClass = mainClass,
      classPath = classpath().map(_.path),
      mainArgs = args()
    )
    Task.log.info(s"Keytool result: $res")
  }

  override lazy val millDiscover: Discover = Discover[this.type]
}

object KeytoolModule extends KeytoolModule
