package mill.api

class BspCompileArguments {
  var arguments: Seq[String] = Seq.empty[String]

  def args: Seq[String] = {
    arguments
  }
}
