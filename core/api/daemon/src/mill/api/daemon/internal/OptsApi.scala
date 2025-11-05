package mill.api.daemon.internal

trait OptsApi {
  def toStringSeq: Seq[String]
  def value: Seq[OptGroupApi]
}

trait OptGroupApi {}

trait OptApi {
  def toString(): String
}
