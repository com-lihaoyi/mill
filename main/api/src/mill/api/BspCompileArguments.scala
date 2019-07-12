package mill.api

class BspCompileArguments {
  var arguments: Seq[String] = Seq.empty[String]

  def args: Seq[String] = {
    arguments
  }

  def setArgs(args: Seq[String]): Unit = {
    arguments = args
  }
}
