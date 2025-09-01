package mill.scalajslib.worker.api

private[scalajslib] sealed trait JsEnvConfig

private[scalajslib] object JsEnvConfig {
  final case class NodeJs(
      executable: String,
      args: List[String],
      env: Map[String, String],
      sourceMap: Boolean
  ) extends JsEnvConfig

  final case class JsDom(
      executable: String,
      args: List[String],
      env: Map[String, String]
  ) extends JsEnvConfig

  final case class ExoegoJsDomNodeJs(
      executable: String,
      args: List[String],
      env: Map[String, String]
  ) extends JsEnvConfig

  final case class Phantom(
      executable: String,
      args: List[String],
      env: Map[String, String],
      autoExit: Boolean
  ) extends JsEnvConfig

  final case class Selenium(
      capabilities: Selenium.Capabilities
  ) extends JsEnvConfig
  object Selenium {
    sealed trait Capabilities
    case class ChromeOptions(headless: Boolean) extends Capabilities
    case class FirefoxOptions(headless: Boolean) extends Capabilities
    case class SafariOptions() extends Capabilities
  }

  final case class Playwright(
      capabilities: Playwright.Capabilities
  ) extends JsEnvConfig
  object Playwright {
    sealed trait Capabilities
    case class ChromeOptions(
        headless: Boolean = true,
        showLogs: Boolean = false,
        debug: Boolean = false,
        launchOptions: List[String] = List(
          "--disable-extensions",
          "--disable-web-security",
          "--allow-running-insecure-content",
          "--disable-site-isolation-trials",
          "--allow-file-access-from-files",
          "--disable-gpu"
        )
    ) extends Capabilities:

      def addLaunchOptions(options: List[String]): ChromeOptions =
        copy(launchOptions = (launchOptions ++ options).distinct)

    object ChromeOptions:
      val default = ChromeOptions()

    case class FirefoxOptions(
        headless: Boolean = true,
        showLogs: Boolean = false,
        debug: Boolean = false,
        firefoxUserPrefs: Map[String, String | Double | Boolean] = Map(
          "security.mixed_content.block_active_content" -> false,
          "security.mixed_content.upgrade_display_content" -> false,
          "security.file_uri.strict_origin_policy" -> false
        )
    ) extends Capabilities:

      def addFirefoxUserPrefs(options: Map[String, String | Double | Boolean])
          : FirefoxOptions =
        copy(firefoxUserPrefs = (firefoxUserPrefs ++ options))

    object FirefoxOptions:
      val default = FirefoxOptions()

    case class WebkitOptions(
        headless: Boolean = true,
        showLogs: Boolean = false,
        debug: Boolean = false,
        launchOptions: List[String] = List(
          "--disable-extensions",
          "--disable-web-security",
          "--allow-running-insecure-content",
          "--disable-site-isolation-trials",
          "--allow-file-access-from-files"
        )
    ) extends Capabilities:

      def addLaunchOptions(options: List[String]): WebkitOptions =
        copy(launchOptions = (launchOptions ++ options).distinct)

    object WebkitOptions:
      val default = WebkitOptions()
  }
}
