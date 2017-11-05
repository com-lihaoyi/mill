import forge.util.JsonFormatters

package object forge extends JsonFormatters{
  val T = define.Target
  type T[T] = define.Target[T]
}
