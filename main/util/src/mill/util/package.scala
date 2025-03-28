package mill

import scala.annotation.nowarn

package object util {
  @nowarn("cat=deprecation")
  type JarManifest = mill.api.JarManifest
  @nowarn("cat=deprecation")
  val JarManifest = mill.api.JarManifest
}
