package mill.scalajslib.worker.jsenv

import mill.scalajslib.worker.api._
import scala.util.chaining.given

object Playwright {
  def apply(config: JsEnvConfig.Playwright) =
    new io.github.thijsbroersen.jsenv.playwright.PWEnv(
      browserName = config.browserName,
      headless = config.headless,
      showLogs = config.showLogs,
      debug = config.debug,
      // pwConfig = config.pwConfig,
      runConfigEnv = config.runConfigEnv,
      launchOptions = config.launchOptions,
      additionalLaunchOptions = config.additionalLaunchOptions
    )
}
