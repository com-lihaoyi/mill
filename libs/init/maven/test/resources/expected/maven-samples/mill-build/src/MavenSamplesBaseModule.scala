package millbuild

import mill.javalib.*
import mill.javalib.publish.*

trait MavenSamplesBaseModule extends MavenModule with PublishModule {

  def jvmId = "zulu:11"

  def publishVersion = "1.0-SNAPSHOT"

}
