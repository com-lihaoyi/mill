import mill.util.JsonFormatters

package object mill extends JsonFormatters{
  val T = define.Target
  type T[T] = define.Target[T]
  val PathRef = mill.eval.PathRef
  type PathRef = mill.eval.PathRef
  type Module = define.Task.Module
  val Module = define.Task.Module
  type CrossModule[T, V] = define.CrossModule[T, V]
  type CrossModule2[T1, T2, V] = define.CrossModule2[T1, T2, V]
  type CrossModule3[T1, T2, T3, V] = define.CrossModule3[T1, T2, T3, V]
}
