package object mill extends mill.api.JsonFormatters {
  val T = define.Target
  type T[T] = define.Target[T]
  val PathRef = mill.api.PathRef
  type PathRef = mill.api.PathRef
  type Module = define.Module
  type Cross[T <: Cross.Module[_]] = define.Cross[T]
  val Cross = define.Cross
  type Agg[T] = mill.api.Loose.Agg[T]
  val Agg = mill.api.Loose.Agg
  type RootModule = mill.main.RootModule
  val RootModule = mill.main.RootModule
  type Args = define.Args
  val Args = define.Args
}
