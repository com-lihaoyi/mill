package object mill extends mill.api.JsonFormatters with mill.util.TokenReaders0 {
  val T = define.Target
  type T[+T] = define.Target[T]
  val Target = define.Target
  type Target[+T] = define.Target[T]
  val PathRef = mill.api.PathRef
  type PathRef = mill.api.PathRef
  type Module = define.Module
  type Cross[T <: Cross.Module[?]] = define.Cross[T]
  val Cross = define.Cross
  type Agg[T] = Seq[T]
  val Agg = Seq

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
