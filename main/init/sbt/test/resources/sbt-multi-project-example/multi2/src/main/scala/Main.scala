object Main extends App {
  println("multi2 can use common sub-project")

  val entity = Entity("id", NestedEntity("value"))

  println("multi2 can use pureconfig dependency")

  import pureconfig._

  implicit def hint[T]: ProductHint[T] = ProductHint[T](ConfigFieldMapping(CamelCase, KebabCase))
}
