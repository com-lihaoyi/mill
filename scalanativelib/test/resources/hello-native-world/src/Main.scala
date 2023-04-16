package hello

object Main extends App {
  // tests lazy val work in Scala 3.2+
  lazy val foo = 1

  println("Hello " + vmName)
  def vmName = sys.props("java.vm.name")
}
