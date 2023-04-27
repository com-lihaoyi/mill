package mill.define

/**
 * Represents an unstructured sequence of command-line arguments that can be
 * passed to Mill commands; similar to `mainargs.Leftover`.
 */
class Args(val value: Seq[String])
object Args {
  /**
   * Constructs an [[Args]] object from `os.Shellable`s
   */
  def apply(chunks: os.Shellable*) = new Args(chunks.flatMap(_.value))
}
