package mill.scalajslib.worker.jsenv

import mill.scalajslib.worker.api._
import org.scalajs.jsenv.nodejs.NodeJSEnv.SourceMap

object NodeJs {
  def apply(config: JsEnvConfig.NodeJs) =
    /* In Mill, `config.sourceMap = true` means that `source-map-support`
     * should be used *if available*, as it is what was used to mean in
     * Scala.js 0.6.x. Scala.js 1.x has 3 states: enable, enable-if-available
     * and disable. The former (enable) *fails* if it cannot load the
     * `source-map-support` module. We must therefore adapt the boolean to
     * one of the two last states.
     */
    new org.scalajs.jsenv.nodejs.NodeJSEnv(
      org.scalajs.jsenv.nodejs.NodeJSEnv.Config()
        .withExecutable(config.executable)
        .withArgs(config.args)
        .withEnv(config.env)
        .withSourceMap(if (config.sourceMap) SourceMap.EnableIfAvailable else SourceMap.Disable)
    )
}
