package mill

package object define {
  @deprecated("Use mill.define.Target instead.", "Mill after 0.11.0-M8")
  type Input[T] = Task.Simple[T]
  @deprecated("Use mill.define.Target instead.", "Mill after 0.11.0-M8")
  type Persistent[T] = Task.Simple[T]
  @deprecated("Use mill.define.Target instead.", "Mill after 0.11.0-M8")
  type Source = Task.Simple[mill.api.PathRef]
  @deprecated("Use mill.define.Target instead.", "Mill after 0.11.0-M8")
  type Sources = Task.Simple[Seq[mill.api.PathRef]]

}
