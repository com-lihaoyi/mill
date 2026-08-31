package mybuild

import build_.package_ as build
import mill.javalib.JavaModule

trait ApplicationModule extends JavaModule {
  override def moduleDeps = Seq(build.core)
}
