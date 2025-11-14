package millbuild

import mill.javalib.*
import mill.javalib.publish.*

trait MavenSamplesBaseModule extends MavenModule with PublishModule {

  def publishVersion = "1.0-SNAPSHOT"

}
