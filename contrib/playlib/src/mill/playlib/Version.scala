package mill.playlib

import mill.{T, Task}
import mill.define.Module
import mill.scalalib._

private[playlib] trait Version extends Module {

  def playVersion: T[String]

  private[playlib] def playMinorVersion: T[String] = Task {
    playVersion().split('.').take(2).mkString(".")
  }

  private[playlib] def playOrganization: Task[String] = Task.Anon {
    if (playVersion().startsWith("2.")) "com.typesafe.play" else "org.playframework"
  }

  private[playlib] def component(id: String): Task[Dep] = Task.Anon {
    mvn"${playOrganization()}::$id::${playVersion()}"
  }
}
