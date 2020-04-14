package mill.scalalib

package object publish extends JsonFormatters {
  @deprecated("Use LocalIvyPublisher instead", "after-0.6.1")
  val LocalPublisher = LocalIvyPublisher
}
