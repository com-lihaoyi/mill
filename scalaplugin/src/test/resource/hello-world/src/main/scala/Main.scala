import scala.collection._ // unused import to check unused imports warning
import java.nio.file.{Files, Paths}

object Main extends App {
  val person = Person.fromString("rockjam:25")
  val greeting = s"hello ${person.name}, your age is: ${person.age}"
  println(greeting)
  val resultPath = Paths.get("target", "workspace", "hello-world", "hello-mill")
  Files.write(resultPath, greeting.getBytes)
}
