package mill.define

case class Args(value: String*)
object Args{
  implicit def createArgs(value: Seq[String]) = new Args(value:_*)
}
