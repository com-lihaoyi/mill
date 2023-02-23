package mill.scalajslib.worker.jsenv

import mill.scalajslib.worker.api._

object JsDom {
  def apply(config: JsEnvConfig.JsDom) =
    new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv(
      org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv.Config()
        .withExecutable(config.executable)
        .withArgs(config.args)
        .withEnv(config.env)
    )
}
