package mill
package playlib

import mill.define.{Target, Task}
import mill.scalalib._

private[playlib] trait Version extends Module{

  def playVersion: T[String]

  private[playlib] def playMinorVersion: T[String] = T {
    playVersion().split("\\.").take(2).mkString(".")
  }

  private[playlib] def component(id: String) = T.task {
    ivy"com.typesafe.play::$id::${playVersion()}"
  }
}
