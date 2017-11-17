package mill

import mill.define.Cross
import utest._

object CrossTests extends TestSuite{

  val tests = Tests{

    def assertEquals[T](value: Cross[T], value1: Cross[T]) = {
      assert(value == value1)
    }
    'single - assertEquals(
      for(a <- Cross(1, 2, 3)) yield a.toString,
      Cross(List((List(1), "1"), (List(2), "2"), (List(3), "3")))
    )



    'double - assertEquals(
      for{
        a <- Cross(1, 2, 3)
        b <- Cross("A", "B", "C")
      } yield b * a,
      Cross(
        List(
          (List("A", 1), "A"),
          (List("B", 1), "B"),
          (List("C", 1), "C"),
          (List("A", 2), "AA"),
          (List("B", 2), "BB"),
          (List("C", 2), "CC"),
          (List("A", 3), "AAA"),
          (List("B", 3), "BBB"),
          (List("C", 3), "CCC")
        )
      )
    )


    'triple - assertEquals(
      for{
        a <- Cross(1, 2)
        b <- Cross("A", "B")
        c <- Cross(true, false)
      } yield b * a + c,
      Cross(
        List(
          (List(true, "A", 1), "Atrue"),
          (List(false, "A", 1), "Afalse"),
          (List(true, "B", 1), "Btrue"),
          (List(false, "B", 1), "Bfalse"),
          (List(true, "A", 2), "AAtrue"),
          (List(false, "A", 2), "AAfalse"),
          (List(true, "B", 2), "BBtrue"),
          (List(false, "B", 2), "BBfalse")
        )
      )
    )


    'filter - assertEquals(
      for{
        a <- Cross(1, 2, 3)
        b <- Cross("A", "B", "C")
        if !(a == 2 && b == "B")
      } yield b * a,
      Cross(
        List(
          (List("A", 1), "A"),
          (List("B", 1), "B"),
          (List("C", 1), "C"),
          (List("A", 2), "AA"),
          (List("C", 2), "CC"),
          (List("A", 3), "AAA"),
          (List("B", 3), "BBB"),
          (List("C", 3), "CCC")
        )
      )
    )


    'middleFilter- assertEquals(
      for{
        a <- Cross(1, 2, 3)
        if a != 2
        b <- Cross("A", "B", "C")
      } yield b * a,
      Cross(
        List(
          (List("A", 1), "A"),
          (List("B", 1), "B"),
          (List("C", 1), "C"),
          (List("A", 3), "AAA"),
          (List("B", 3), "BBB"),
          (List("C", 3), "CCC")
        )
      )
    )


    'nestedComprehension1 - assertEquals(
      for{
        (a, b) <- for(a <- Cross(1, 2); b <- Cross("A", "B")) yield (a, b)
        c <- Cross(true, false)
      } yield b * a + c,
      Cross(
        List(
          (List(true, "A", 1), "Atrue"),
          (List(false, "A", 1), "Afalse"),
          (List(true, "B", 1), "Btrue"),
          (List(false, "B", 1), "Bfalse"),
          (List(true, "A", 2), "AAtrue"),
          (List(false, "A", 2), "AAfalse"),
          (List(true, "B", 2), "BBtrue"),
          (List(false, "B", 2), "BBfalse")
        )
      )
    )

    'nestedComprehension2 - assertEquals(
      for{
        a <- Cross(1, 2)
        (b, c) <- for(b <- Cross("A", "B"); c <- Cross(true, false)) yield (b, c)
      } yield b * a + c,
      Cross(
        List(
          (List(true, "A", 1), "Atrue"),
          (List(false, "A", 1), "Afalse"),
          (List(true, "B", 1), "Btrue"),
          (List(false, "B", 1), "Bfalse"),
          (List(true, "A", 2), "AAtrue"),
          (List(false, "A", 2), "AAfalse"),
          (List(true, "B", 2), "BBtrue"),
          (List(false, "B", 2), "BBfalse")
        )
      )
    )

  }
}
