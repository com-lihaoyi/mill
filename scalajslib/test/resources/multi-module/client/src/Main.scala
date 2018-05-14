import shared.Utils

object Main extends App {
  val result = Utils.add(1, 2)
  println(s"Hello from ${Lib.vmName}, result is: ${result}")
}
