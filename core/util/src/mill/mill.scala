package object mill extends mill.api.JsonFormatters with mill.util.TokenReaders0 {
  type T[+T] = api.Task.Simple[T]
  val PathRef = mill.api.PathRef
  type PathRef = mill.api.PathRef
  type Module = api.Module
  type Cross[T <: Cross.Module[?]] = api.Cross[T]
  val Cross = api.Cross
  @deprecated("Use Seq[T] instead", "Mill 0.13.0-M1")
  type Agg[T] = Seq[T]
  @deprecated("Use Seq instead", "Mill 0.13.0-M1")
  val Agg = Seq

  type Args = api.Args
  val Args = api.Args

  val Task = api.Task
  type Task[+T] = api.Task[T]

  type Command[+T] = api.Task.Command[T]
  type Worker[+T] = api.Task.Worker[T]
  type Source = api.Task.Simple[PathRef]
  type Sources = api.Task.Simple[Seq[PathRef]]

  type TaskModule = api.TaskModule
}
