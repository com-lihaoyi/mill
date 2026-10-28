package mill.api.daemon.internal

import mill.api.daemon.experimental

@experimental
trait OptsApi {
  def toStringSeq: Seq[String]
  def value: Seq[OptGroupApi]
}

@experimental
trait OptGroupApi {}

@experimental
trait OptApi {
  def toString(): String
}
