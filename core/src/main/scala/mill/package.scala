import mill.util.JsonFormatters

package object mill extends JsonFormatters{
  val T = define.Target
  type T[T] = define.Target[T]
  val PathRef = mill.eval.PathRef
  type PathRef = mill.eval.PathRef
  type Module = define.Module
  val Module = define.Module
  type CrossModule[T] = define.CrossModule[T]
}
