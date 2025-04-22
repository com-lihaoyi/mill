object Main extends App {
  val person = Person.fromString("rockjam:25")
  println(s"hello ${person.name}, your age is: ${person.age}")
}
