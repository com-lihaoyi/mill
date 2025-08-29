package millbuild

import mill.javalib._

trait ApplicationLibraryBaseModule extends MavenModule {

  def javacOptions = super.javacOptions() ++
    Seq("-source", "1.8", "-target", "1.8")

}
