package mill.scalajslib.worker.jsenv

import mill.scalajslib.worker.api._

object ExoegoJsDomNodeJs {
  def apply(config: JsEnvConfig.ExoegoJsDomNodeJs) =
    new net.exoego.jsenv.jsdomnodejs.JSDOMNodeJSEnv(
      net.exoego.jsenv.jsdomnodejs.JSDOMNodeJSEnv.Config()
        .withExecutable(config.executable)
        .withArgs(config.args)
        .withEnv(config.env)
    )
}
