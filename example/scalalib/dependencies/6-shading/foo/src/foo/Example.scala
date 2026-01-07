package foo

// Note: We import the shaded package name, not the original
import shaded.fansi.{Color, Str}

object Example {
  def main(args: Array[String]): Unit = {
    // Using the shaded fansi library for colored terminal output
    val colored = Color.Red("Hello") ++ Str(", ") ++ Color.Green("World!")
    println(s"Colored text using shaded fansi: $colored")
    println(s"Plain text: ${colored.plainText}")
  }
}
