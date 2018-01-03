import mill.util.JsonFormatters

package object mill extends JsonFormatters{
  val T = define.Target
  type T[T] = define.Target[T]
  val PathRef = mill.eval.PathRef
  type PathRef = mill.eval.PathRef
  type Module = define.Task.Module
  type CrossModule[T, V] = define.CrossModule[T, V]
}
