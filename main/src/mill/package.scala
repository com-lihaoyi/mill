package object mill extends mill.api.JsonFormatters with mill.main.TokenReaders0 {
  val T = define.Target
  type T[+T] = define.Target[T]
  val Target = define.Target
  type Target[+T] = define.Target[T]
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
  type Input[T] = define.Target[T]
  type Persistent[T] = define.Target[T]
  type Source = define.Target[mill.api.PathRef]
  type Sources = define.Target[Seq[mill.api.PathRef]]

  type TaskModule = define.TaskModule
}
