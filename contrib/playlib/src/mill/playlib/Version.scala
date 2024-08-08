package mill.playlib

import mill.{Task, T}
import mill.define.Module
import mill.scalalib._

private[playlib] trait Version extends Module {

  def playVersion: T[String]

  private[playlib] def playMinorVersion: T[String] = Task {
    playVersion().split('.').take(2).mkString(".")
  }

  private[playlib] def playOrganization: T[String] = Task {
    if (playVersion().startsWith("2.")) "com.typesafe.play" else "org.playframework"
  }

  private[playlib] def component(id: String) = Task.anon {
    ivy"${playOrganization()}::$id::${playVersion()}"
  }
}
