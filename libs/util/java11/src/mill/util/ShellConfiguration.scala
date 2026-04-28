package mill.util

sealed trait ShellConfiguration {
  def name: String
  def configPath: os.Path
  def formatEnvVar(name: String, value: String): String
  def envVarPattern(name: String): scala.util.matching.Regex

  def formatComment(text: String): String = s"# $text"

  def upsertEnvVar(name: String, value: String): Unit = {
    val content = os.read(configPath)
    val pattern = envVarPattern(name)
    val newLine = formatEnvVar(name, value)
    val newContent = pattern.findFirstMatchIn(content) match {
      case Some(_) =>
        pattern.replaceFirstIn(content, scala.util.matching.Regex.quoteReplacement(newLine))
      case None =>
        if (content.endsWith("\n")) content + newLine + "\n"
        else content + "\n" + newLine + "\n"
    }
    val tmp = os.temp(newContent, dir = configPath / os.up)
    try os.move(tmp, configPath, replaceExisting = true, atomicMove = true)
    catch {
      case _: java.nio.file.AtomicMoveNotSupportedException =>
        os.move(tmp, configPath, replaceExisting = true)
    }
  }

  def previewUpsert(envVars: Seq[(String, String)]): String = {
    val tmpCopy =
      os.temp(os.read(configPath), dir = configPath / os.up, prefix = ".mill-preview-")
    try {
      envVars.foreach { case (envName, value) =>
        withConfigPath(tmpCopy).upsertEnvVar(envName, value)
      }
      if (!ShellConfiguration.findOnPath("git")) return "(diff preview unavailable — git not found)"
      val result = os.proc("git", "diff", "--no-index", "--", configPath, tmpCopy)
        .call(mergeErrIntoOut = true, check = false)
      result.out.text()
    } finally os.remove(tmpCopy)
  }

  def withConfigPath(newPath: os.Path): ShellConfiguration
}

object ShellConfiguration {

  def findOnPath(binaryName: String): Boolean = {
    Option(System.getenv("PATH")).exists { path =>
      val dirs = path.split(java.io.File.pathSeparatorChar)
      val extensions = if (isWindows) Seq("", ".exe", ".cmd", ".bat") else Seq("")
      dirs.exists(dir =>
        extensions.exists(ext =>
          java.nio.file.Files.isExecutable(java.nio.file.Paths.get(dir, binaryName + ext))
        )
      )
    }
  }

  val isMac: Boolean = System.getProperty("os.name").toLowerCase.contains("mac")
  val isWindows: Boolean = System.getProperty("os.name").toLowerCase.contains("win")

  def installed: Seq[ShellConfiguration] = detectors.flatMap(_.detect)

  private val detectors: Seq[ShellDetector] =
    Seq(BashDetector, ZshDetector, FishDetector, NushellDetector, PowerShellDetector)

  private def escapeForPosix(value: String): String =
    value
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("$", "\\$")
      .replace("`", "\\`")
      .replace("!", "\\!")
      .replace("\n", "\\n")

  // Matches the content between quotes, allowing escaped characters (e.g. \")
  private val quotedContent = """(?:[^"\n\\]|\\.)*"""

  case class Bash(configPath: os.Path) extends ShellConfiguration {
    val name = "Bash"
    def formatEnvVar(name: String, value: String): String =
      s"""export $name="${escapeForPosix(value)}""""
    def envVarPattern(name: String): scala.util.matching.Regex =
      s"""(?m)^export ${java.util.regex.Pattern.quote(name)}="$quotedContent"$$""".r
    def withConfigPath(newPath: os.Path): Bash = copy(configPath = newPath)
  }

  case class Zsh(configPath: os.Path) extends ShellConfiguration {
    val name = "Zsh"
    def formatEnvVar(name: String, value: String): String =
      s"""export $name="${escapeForPosix(value)}""""
    def envVarPattern(name: String): scala.util.matching.Regex =
      s"""(?m)^export ${java.util.regex.Pattern.quote(name)}="$quotedContent"$$""".r
    def withConfigPath(newPath: os.Path): Zsh = copy(configPath = newPath)
  }

  case class Fish(configPath: os.Path) extends ShellConfiguration {
    val name = "Fish"
    def formatEnvVar(name: String, value: String): String =
      s"""set -gx $name "${escapeForPosix(value)}""""
    def envVarPattern(name: String): scala.util.matching.Regex =
      s"""(?m)^set -gx ${java.util.regex.Pattern.quote(name)} "$quotedContent"$$""".r
    def withConfigPath(newPath: os.Path): Fish = copy(configPath = newPath)
  }

  case class Nushell(configPath: os.Path) extends ShellConfiguration {
    val name = "Nushell"
    private def escapeValue(value: String): String =
      value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("'", "\\'")
        .replace("\n", "\\n")
    def formatEnvVar(name: String, value: String): String =
      s"""$$env.${name} = "${escapeValue(value)}""""
    def envVarPattern(name: String): scala.util.matching.Regex =
      s"""(?m)^\\$$env\\.${java.util.regex.Pattern.quote(name)} = "$quotedContent"$$""".r
    def withConfigPath(newPath: os.Path): Nushell = copy(configPath = newPath)
  }

  case class PowerShell(configPath: os.Path) extends ShellConfiguration {
    val name = "PowerShell"
    override def formatComment(text: String): String = s"# $text"
    private def escapeValue(value: String): String =
      value
        .replace("`", "``")
        .replace("\"", "`\"")
        .replace("\n", "`n")
    def formatEnvVar(name: String, value: String): String =
      s"""$$env:${name} = "${escapeValue(value)}""""
    def envVarPattern(name: String): scala.util.matching.Regex =
      s"""(?m)^\\$$env:${java.util.regex.Pattern.quote(name)} = "$quotedContent"$$""".r
    def withConfigPath(newPath: os.Path): PowerShell = copy(configPath = newPath)
  }

  // --- Detection logic (private) ---

  private sealed trait ShellDetector {
    def detect: Option[ShellConfiguration]
  }

  private object BashDetector extends ShellDetector {
    def detect: Option[ShellConfiguration] = {
      if (isMac) return None
      if (!findOnPath("bash")) return None
      val bashrc = os.home / ".bashrc"
      if (os.exists(bashrc)) Some(Bash(bashrc)) else None
    }
  }

  private object ZshDetector extends ShellDetector {
    def detect: Option[ShellConfiguration] = {
      if (!findOnPath("zsh")) return None
      runConfigDetection("zsh", Seq("-c", """echo "${ZDOTDIR:-$HOME}/.zshrc"""")).map(Zsh(_))
    }
  }

  private object FishDetector extends ShellDetector {
    def detect: Option[ShellConfiguration] = {
      if (!findOnPath("fish")) return None
      runConfigDetection("fish", Seq("-c", "echo $__fish_config_dir/config.fish")).map(Fish(_))
    }
  }

  private object NushellDetector extends ShellDetector {
    def detect: Option[ShellConfiguration] = {
      if (!findOnPath("nu")) return None
      runConfigDetection("nu", Seq("-c", "echo $nu.config-path")).map(Nushell(_))
    }
  }

  private object PowerShellDetector extends ShellDetector {
    val binaryNames = Seq("pwsh", "powershell")

    def detect: Option[ShellConfiguration] = {
      binaryNames.view.flatMap { bin =>
        if (!findOnPath(bin)) None
        else runConfigDetection(bin, Seq("-c", "echo $PROFILE")).map(PowerShell(_))
      }.headOption
    }
  }

  private def runConfigDetection(binary: String, args: Seq[String]): Option[os.Path] = {
    scala.util.Try {
      os.proc(binary +: args).call(mergeErrIntoOut = true, check = false)
    }.toOption
      .filter(_.exitCode == 0)
      .map(_.out.text().trim)
      .filter(_.nonEmpty)
      .map(os.Path(_))
      .filter(os.exists(_))
  }
}
