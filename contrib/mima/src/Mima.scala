package mill.mima

import mill._
import mill.define.Command
import mill.scalalib._

trait Mima extends MimaBase with OfflineSupportModule {

  override def prepareOffline(all: mainargs.Flag): Command[Unit] = {
    val task = if (all.value) {
      resolvedMimaPreviousArtifacts.map(_ => ())
    } else {
      T.task { () }
    }
    T.command {
      super.prepareOffline(all)()
      task()
    }
  }

}
