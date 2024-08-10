package object mill extends mill.api.JsonFormatters {
  @deprecated("Use Task.*", "0.12.0")
  val T = define.Task
  type T[+T] = define.Task[T]

  val Target = define.Task
  type Target[+T] = define.Task[T]
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

  val Task = define.Task
  type Task[+T] = define.Task[T]

  type Command[+T] = define.Command[T]
  type Worker[+T] = define.Worker[T]
  type Input[T] = define.Task[T]
  type Persistent[T] = define.Task[T]
  type Source = define.Task[mill.api.PathRef]
  type Sources = define.Task[Seq[mill.api.PathRef]]

  type TaskModule = define.TaskModule
}
