object Main extends App {
  val person = Person.fromString("rockjam:25")
  val greeting = s"hello ${person.name}, your age is: ${person.age}"
  println(greeting)
}
