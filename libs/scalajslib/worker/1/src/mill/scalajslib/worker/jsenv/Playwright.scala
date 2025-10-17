package mill.scalajslib.worker.jsenv

import mill.scalajslib.worker.api._

object Playwright {
  def apply(config: JsEnvConfig.Playwright) = config.capabilities match
    case options: JsEnvConfig.Playwright.ChromeOptions =>
      io.github.thijsbroersen.jsenv.playwright.PlaywrightJSEnv.chrome(
        headless = options.headless,
        showLogs = options.showLogs,
        debug = options.debug,
        launchOptions = options.launchOptions
      )
    case options: JsEnvConfig.Playwright.FirefoxOptions =>
      io.github.thijsbroersen.jsenv.playwright.PlaywrightJSEnv.firefox(
        headless = options.headless,
        showLogs = options.showLogs,
        debug = options.debug,
        firefoxUserPrefs = options.firefoxUserPrefs
      )
    case options: JsEnvConfig.Playwright.WebkitOptions =>
      io.github.thijsbroersen.jsenv.playwright.PlaywrightJSEnv.webkit(
        headless = options.headless,
        showLogs = options.showLogs,
        debug = options.debug,
        launchOptions = options.launchOptions
      )
}
