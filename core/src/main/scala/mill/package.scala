import mill.util.JsonFormatters

package object mill extends JsonFormatters{
  val T = define.Task
  type T[T] = define.Task[T]
  val PathRef = mill.eval.PathRef
  type PathRef = mill.eval.PathRef
}
