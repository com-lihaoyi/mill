package mill.api

import scala.collection.immutable.{Map as ScalaMap, Seq as ScalaSeq}

/**
 * Define how a class is appended to another innstance, when it is read from YAML.
 *
 * Example:
 * {{{
 * case class MyConfig(value: Seq[String]) extends AppendableFromYaml[MyConfig]
 * }}}
 */
trait AppendableFromYaml[T] {
  def appendFromYaml(value: Any): T
}

object AppendableFromYaml {
  class Seq(underlying: ScalaSeq[Any]) extends AppendableFromYaml[ScalaSeq[Any]] {
    override def appendFromYaml(value: Any): ScalaSeq[Any] = {
      underlying ++ value.asInstanceOf[ScalaSeq[Any]]
    }
  }

  class Map(underlying: ScalaMap[String, Any]) extends AppendableFromYaml[ScalaMap[String, Any]] {
    override def appendFromYaml(value: Any): ScalaMap[String, Any] = {
      underlying ++ value.asInstanceOf[ScalaMap[String, Any]]
    }
  }
}
