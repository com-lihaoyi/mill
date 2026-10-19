package millbuild

import mill.*
import mill.api.{AppendableFromYaml, Task}

trait MyModule extends Module {
  def configSeq: T[Seq[String]] = Task {
    Seq("A")
  }
  def configClass: T[MyConfig] = Task {
    MyConfig(Seq("M"))
  }
  def configMap: T[Map[String, MyConfig]] = Task {
    Map("default" -> MyConfig(Seq("X")))
  }
}

case class MyConfig(value: Seq[String]) extends AppendableFromYaml[MyConfig]
    derives upickle.ReadWriter {

  override def appendFromYaml(value: Any): MyConfig = value match {
    case c: MyConfig => MyConfig(this.value ++ c.value)
  }
}
