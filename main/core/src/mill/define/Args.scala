package mill.define

class Args(val value: Seq[String])
object Args{
  implicit def createArgs(value: Seq[String]) = new Args(value)
  def apply(chunks: os.Shellable*) =  new Args(chunks.flatMap(_.value))
}
