package millbuild

import mill.javalib._

trait `7.0BaseModule` extends MavenModule {

  def javacOptions = super.javacOptions() ++
    Seq("-source", "11", "-target", "11")

  def jvmId = "zulu:11"

}
