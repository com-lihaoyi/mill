object Person {
  def fromString(s: String): Person = {
    val Array(name, age) = s.split(":")
    Person(
      name,
      age.toInt
    )
  }
}

case class Person(name: String, age: Int)
