import monocle.macros.GenLens
import org.scalatest.FunSuite

class Test extends FunSuite {

  test("multi1 can use common sub-project") {
    val entity = Entity("id", NestedEntity("value"))
  }

  test("multi1 can use monocle dependency ") {
    val idLens = GenLens[Entity](_.id)
  }
}
