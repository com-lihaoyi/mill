package yamlconfigappendable

import mill.*
import mill.api.Task
import mill.api.internal.AppendableFromYaml

trait MyModule extends Module {
  def configSeq: T[Seq[String]] = Task {
    Seq("A")
  }

  def configTask: T[MyConfig] = Task {
    MyConfig(Seq("A"))
  }

  def configTaskExt: T[MyConfig] = Task {
    configTask()
  }
}

case class MyConfig(value: Seq[String]) extends AppendableFromYaml[MyConfig]
    derives upickle.ReadWriter {
  override def appendFromYaml(value: Any): MyConfig = value match {
    case c: MyConfig =>
      println(s"Concat ${this.value} with ${c.value}")
      MyConfig(this.value ++ c.value)
    case x => sys.error(s"Cannot append ${x.getClass.getName} to a ${this.getClass.getName}")
  }
}
