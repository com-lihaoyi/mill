package mill.api.internal

import scala.collection.immutable.Seq as ScalaSeq

trait AppendableFromYaml[T] {
  def appendFromYaml(value: Any): T
}

object AppendableFromYaml {
  class Seq(underlying: ScalaSeq[Any]) extends AppendableFromYaml[ScalaSeq[Any]] {
    def appendFromYaml(value: Any): ScalaSeq[Any] = {
      underlying ++ value.asInstanceOf[ScalaSeq[Any]]
    }
  }
}
