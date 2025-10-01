package example

trait Show[A]

object Show {
  implicit def option[A](using s: Show[A]): Show[Option[A]] = ???
}

object Example {
  def main(args: Array[String]): Unit =
    println(implicitly[Show[Option[String]]])
}
