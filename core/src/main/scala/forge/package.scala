import forge.define.ZipTarget
import forge.util.JsonFormatters

package object forge extends ZipTarget with JsonFormatters{
  val Target = define.Target
  type Target[T] = define.Target[T]
}
