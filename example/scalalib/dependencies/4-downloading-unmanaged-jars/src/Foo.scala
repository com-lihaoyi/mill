package foo
import com.williamfiset.fastjavaio.InputReader

object Foo {

  def main(args: Array[String]): Unit = {
    val filePath = args(0)
    val fi = InputReader(new java.io.FileInputStream(filePath))
    try {
      while (
        fi.nextLine() match {
          case null => false
          case line =>
            println(line)
            true
        }
      ) ()
    } finally {
      fi.close()
    }
  }
}
