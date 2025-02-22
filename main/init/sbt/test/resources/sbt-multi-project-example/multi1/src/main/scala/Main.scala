import monocle.macros.GenLens

object Main extends App {
  println("multi1 can use common sub-project")

  val entity = Entity("id", NestedEntity("value"))

  println("multi1 can use monocle dependency")

  val idLens = GenLens[Entity](_.id)
}
