package millbuild

import mill.javalib._
import mill.javalib.publish._

trait MavenSamplesBaseModule extends MavenModule with PublishModule {

  def publishVersion = "1.0-SNAPSHOT"

}
