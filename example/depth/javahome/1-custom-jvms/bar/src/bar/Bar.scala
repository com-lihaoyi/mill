package bar

object Bar {
  def main(args: Array[String]): Unit = {
    println(s"Bar running on Java ${sys.props("java.version")}")
  }
}
