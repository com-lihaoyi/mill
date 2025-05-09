package mill.spotless

import com.diffplug.spotless.Provisioner

import java.nio.charset.Charset

trait FormatterContext {
  def encoding: Charset
  def provisioner: Provisioner
  def resolver: PathResolver
}
object FormatterContext {

  def apply(encoding: Charset, provisioner: Provisioner, resolver: PathResolver): FormatterContext =
    Impl(encoding, provisioner, resolver)

  private case class Impl(encoding: Charset, provisioner: Provisioner, resolver: PathResolver)
      extends FormatterContext
}
