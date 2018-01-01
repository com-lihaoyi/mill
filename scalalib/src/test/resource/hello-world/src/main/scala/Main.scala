import scala.collection._ // unused import to check unused imports warning
import java.nio.file.{Files, Paths}

object Main extends App {

  val person = Person.fromString("rockjam:25")
  val greeting = s"hello ${person.name}, your age is: ${person.age}"
  println(greeting)
  val resultPath = Paths.get(args(0))
  Files.createDirectories(resultPath.getParent)
  Files.write(resultPath, greeting.getBytes)
}
