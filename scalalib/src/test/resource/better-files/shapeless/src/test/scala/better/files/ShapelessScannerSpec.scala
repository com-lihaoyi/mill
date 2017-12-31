package better.files

import shapeless._

class ShapelessScannerSpec extends CommonSpec {
  import ShapelessScanner._

  val text = """
    12 Bob True
    13 Mary False
    26 Rick True
  """

  "Shapeless Scanner" should "parse HList" in {
    val in = Scanner(text)

    type Row = Int :: String :: Boolean :: HNil
    val out = Seq.fill(3)(in.next[Row])
    assert(out == Seq(
      12 :: "Bob" :: true :: HNil,
      13 :: "Mary" :: false :: HNil,
      26 :: "Rick" :: true :: HNil
    ))
  }

  "Shapeless Scanner" should "parse case class" in {
    val in = Scanner(text)

    case class Person(id: Int, name: String, isMale: Boolean)
    assert(in.next[Iterator[Person]].map(_.id).sum == 51)
  }
}
