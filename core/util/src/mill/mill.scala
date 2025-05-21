package object mill extends mill.define.JsonFormatters with mill.util.TokenReaders0 {
  type T[+T] = define.Task.Cached[T]
  type Target[+T] = define.Task.Cached[T]
  val PathRef = mill.define.PathRef
  type PathRef = mill.define.PathRef
  type Module = define.Module
  type Cross[T <: Cross.Module[?]] = define.Cross[T]
  val Cross = define.Cross
  @deprecated("Use Seq[T] instead", "Mill 0.13.0-M1")
  type Agg[T] = Seq[T]
  @deprecated("Use Seq instead", "Mill 0.13.0-M1")
  val Agg = Seq

  type Args = define.Args
  val Args = define.Args

  val Task = define.Task
  type Task[+T] = define.Task[T]

  type Command[+T] = define.Task.Command[T]
  type Worker[+T] = define.Task.Worker[T]
  type Source = define.Task.Cached[PathRef]
  type Sources = define.Task.Cached[Seq[PathRef]]

  type TaskModule = define.TaskModule
}
