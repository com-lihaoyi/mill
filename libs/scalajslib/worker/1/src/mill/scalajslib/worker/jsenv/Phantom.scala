package mill.scalajslib.worker.jsenv

import mill.scalajslib.worker.api._

object Phantom {
  def apply(config: JsEnvConfig.Phantom) =
    org.scalajs.jsenv.phantomjs.PhantomJSEnv(
      org.scalajs.jsenv.phantomjs.PhantomJSEnv.Config()
        .withExecutable(config.executable)
        .withArgs(config.args)
        .withEnv(config.env)
    )
}
