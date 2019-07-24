package mill.api

/**
  * Data structure to represent Bsp client-specified
  * compilation arguments
  */
class BspCompileArguments {
  var arguments: Seq[String] = Seq.empty[String]

  /**
    * Return the compilation arguments specified by the
    * Bsp client, which may or may not be found in the
    * compiler options of any module from the build file.
    */
  def args: Seq[String] = {
    arguments
  }
}
