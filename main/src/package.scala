import mill.util.JsonFormatters

package object mill extends JsonFormatters{
  val T = define.Target
  type T[T] = define.Target[T]
  val PathRef = mill.api.PathRef
  type PathRef = mill.api.PathRef
  type Module = define.Module
  type Cross[T] = define.Cross[T]
  type Agg[T] = mill.api.Loose.Agg[T]
  val Agg = mill.api.Loose.Agg
}
