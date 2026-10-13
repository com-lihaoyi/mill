package app

object App {
  var result: Int = 0

  // No println: the JS console does not exist in a Wasm component
  def main(args: Array[String]): Unit =
    result = args.length + 42
}
