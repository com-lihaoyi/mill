package mill
package object define{
  type Source = define.Target[mill.api.PathRef]
  type Sources = define.Target[Seq[mill.api.PathRef]]
  type Input[T] = define.Target[T]
  type Persistent[T] = define.Target[T]
}
