package mill.scalalib

import org.eclipse.aether.RepositorySystem

import scala.util.Using.Releasable

package object publish extends JsonFormatters {
  given Releasable[RepositorySystem] = _.shutdown()
}
