package mill

import mill.define.Cross
import utest._

object CrossTests extends TestSuite{

  val tests = Tests{
    'test - {
      Cross.test()
    }
//    def assertEquals[T](value: Cross[T], value1: Cross[T]) = {
//      assert(value == value1)
//    }
//    'map - assertEquals(
//      for(a <- Cross(1, 2, 3)) yield a.toString,
//      Cross(1 -> "1", 2 -> "2", 3 -> "3")
//    )
//    'flatMapFilter - assertEquals(
//      for{
//        a <- Cross(1, 2)
//        b <- Cross("A", "B")
//        if !(a == 2 && b == "B")
//      } yield b * a,
//      Cross(
//        (1 -> ("A" -> "A")),
//        (1 -> ("B" -> "B")),
//        (2 -> ("A" -> "AA"))
//      )
//    )
//    'reuse - {
//      val matrix = for{
//        a <- Cross(1, 2)
//        b <- Cross("A", "B")
//        if !(a == 2 && b == "B")
//      } yield ()
//      assertEquals(
//        for((a, (b, _)) <- matrix)
//        yield b * a,
//        Cross(
//          (1 -> ("A" -> "A")),
//          (1 -> ("B" -> "B")),
//          (2 -> ("A" -> "AA"))
//        )
//      )
//    }


  }
}
