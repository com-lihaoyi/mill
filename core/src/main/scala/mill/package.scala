import mill.util.JsonFormatters

package object mill extends JsonFormatters{
  val T = define.Target
  type T[T] = define.Target[T]
  val PathRef = mill.eval.PathRef
  type PathRef = mill.eval.PathRef
  type Module = define.Module
  type Cross[T] = define.Cross[T]
  type Agg[T] = util.Loose.Agg[T]
  val Agg = util.Loose.Agg
}
