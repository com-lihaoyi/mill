package millbuild

import mill.*
import mill.api.Task

trait MyModule extends Module {
  def configSeq: T[Seq[String]] = Task {
    Seq("A")
  }
  def configClass: T[MyConfig] = Task {
    MyConfig(Seq("A"))
  }
}

case class MyConfig(value: Seq[String]) derives upickle.ReadWriter {}
