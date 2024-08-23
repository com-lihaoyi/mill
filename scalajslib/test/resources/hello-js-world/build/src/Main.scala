object Main extends App {

  println("Hello " + vmName)

  def vmName = sys.props("java.vm.name")
}
