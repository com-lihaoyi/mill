package mill

package object define {
  @deprecated("Use mill.define.Target instead.", "Mill after 0.11.0-M8")
  type Input[T] = Target[T]
  @deprecated("Use mill.define.Target instead.", "Mill after 0.11.0-M8")
  type Persistent[T] = Target[T]
  @deprecated("Use mill.define.Target instead.", "Mill after 0.11.0-M8")
  type Source = Target[mill.api.PathRef]
  @deprecated("Use mill.define.Target instead.", "Mill after 0.11.0-M8")
  type Sources = Target[Seq[mill.api.PathRef]]

}
