import scala.collection._
import java.nio.file.{Files, Paths}
import java.sql.Date
import java.time.LocalDate
import javax.swing.JButton

import Main.{args, greeting}
object Main0{
  def apply(s: String, greeting: String) = {
    val resultPath = Paths.get(s)
    Files.createDirectories(resultPath.getParent)
    Files.write(resultPath, greeting.getBytes)
  }
}
object Main extends App {
  new JButton("hello from javax")
  val now = Date.valueOf(LocalDate.now())
  println(s"Today is the date: ${now}")
  val person = Person.fromString("rockjam:25")
  val greeting = s"hello ${person.name}, your age is: ${person.age}"
  println(greeting)
  Main0(args(0), greeting)
}
