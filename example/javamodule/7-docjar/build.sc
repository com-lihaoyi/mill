//// SNIPPET:BUILD

import mill._, javalib._

object foo extends JavaModule {
  def javadocOptions = Seq("-quiet")
}


//// SNIPPET:SCALA3

