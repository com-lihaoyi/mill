import forge.util.JsonFormatters

package object forge extends JsonFormatters{
  val T = define.Task
  type T[T] = define.Task[T]
}
