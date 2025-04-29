package mill.spotless

import com.diffplug.spotless.Provisioner
import os.{Path, RelPath}

import java.io.File
import java.nio.charset.Charset
import java.util

trait SpotlessContext extends Provisioner, PathResolver {
  def encoding: Charset
}
object SpotlessContext {

  def apply(encoding: Charset, provisioner: Provisioner, resolver: PathResolver): SpotlessContext =
    new Impl(encoding, provisioner, resolver)

  private class Impl(val encoding: Charset, provisioner: Provisioner, resolver: PathResolver)
      extends SpotlessContext {
    def provisionWithTransitives(
        withTransitives: Boolean,
        mavenCoordinates: util.Collection[String]
    ) =
      provisioner.provisionWithTransitives(withTransitives, mavenCoordinates)

    def path(rel: RelPath) = resolver.path(rel)
  }
}
