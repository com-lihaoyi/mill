package mill.util

sealed trait ShellConfiguration {
  def name: String
  def binaryNames: Seq[String]
  def configPathDetectionArgs: Seq[String]
  def formatEnvVar(name: String, value: String): String
  def envVarPattern(name: String): scala.util.matching.Regex
  protected def escapeValue(value: String): String

  def formatComment(text: String): String = s"# $text"

  def isInstalled: Boolean = binaryNames.exists(ShellConfiguration.findOnPath)

  def configFilePath: Option[os.Path] = {
    binaryNames.view.flatMap { bin =>
      val result = scala.util.Try {
        os.proc(bin +: configPathDetectionArgs)
          .call(mergeErrIntoOut = true, check = false)
      }
      result.toOption
        .filter(_.exitCode == 0)
        .map(_.out.text().trim)
        .filter(_.nonEmpty)
        .map(os.Path(_))
        .filter(os.exists(_))
    }.headOption
  }

  def upsertEnvVar(
      configPath: os.Path,
      name: String,
      value: String
  ): Unit = {
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

  def previewUpsert(
      configPath: os.Path,
      envVars: Seq[(String, String)]
  ): String = {
    val tmpCopy = os.temp(os.read(configPath), dir = configPath / os.up, prefix = ".mill-preview-")
    try {
      envVars.foreach { case (envName, value) => upsertEnvVar(tmpCopy, envName, value) }
      val result = os.proc("git", "diff", "--no-index", "--", configPath, tmpCopy)
        .call(mergeErrIntoOut = true, check = false)
      result.out.text()
    } finally os.remove(tmpCopy)
  }
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

  val all: Seq[ShellConfiguration] = Seq(Bash, Zsh, Fish, Nushell, PowerShell)

  def installedWithConfig: Seq[(ShellConfiguration, os.Path)] =
    all.flatMap(shell =>
      if (shell.isInstalled) shell.configFilePath.map(p => (shell, p))
      else None
    )

  case object Bash extends ShellConfiguration {
    val name = "Bash"
    val binaryNames = Seq("bash")
    val configPathDetectionArgs = Seq.empty[String]

    override def configFilePath: Option[os.Path] = {
      if (isMac) return None
      val bashrc = os.home / ".bashrc"
      if (os.exists(bashrc)) Some(bashrc) else None
    }

    protected def escapeValue(value: String): String =
      value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("$", "\\$")
        .replace("`", "\\`")
        .replace("!", "\\!")
        .replace("\n", "\\n")

    def formatEnvVar(name: String, value: String): String =
      s"""export $name="${escapeValue(value)}""""

    def envVarPattern(name: String): scala.util.matching.Regex =
      s"""(?m)^export ${java.util.regex.Pattern.quote(name)}="[^"\n]*"$$""".r
  }

  case object Zsh extends ShellConfiguration {
    val name = "Zsh"
    val binaryNames = Seq("zsh")
    val configPathDetectionArgs = Seq("-c", """echo "${ZDOTDIR:-$HOME}/.zshrc"""")

    protected def escapeValue(value: String): String =
      value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("$", "\\$")
        .replace("`", "\\`")
        .replace("!", "\\!")
        .replace("\n", "\\n")

    def formatEnvVar(name: String, value: String): String =
      s"""export $name="${escapeValue(value)}""""

    def envVarPattern(name: String): scala.util.matching.Regex =
      s"""(?m)^export ${java.util.regex.Pattern.quote(name)}="[^"\n]*"$$""".r
  }

  case object Fish extends ShellConfiguration {
    val name = "Fish"
    val binaryNames = Seq("fish")
    val configPathDetectionArgs = Seq("-c", "echo $__fish_config_dir/config.fish")

    protected def escapeValue(value: String): String =
      value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("$", "\\$")
        .replace("!", "\\!")
        .replace("\n", "\\n")

    def formatEnvVar(name: String, value: String): String =
      s"""set -gx $name "${escapeValue(value)}""""

    def envVarPattern(name: String): scala.util.matching.Regex =
      s"""(?m)^set -gx ${java.util.regex.Pattern.quote(name)} "[^"\n]*"$$""".r
  }

  case object Nushell extends ShellConfiguration {
    val name = "Nushell"
    val binaryNames = Seq("nu")
    val configPathDetectionArgs = Seq("-c", "echo $nu.config-path")

    protected def escapeValue(value: String): String =
      value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("'", "\\'")
        .replace("\n", "\\n")

    def formatEnvVar(name: String, value: String): String =
      s"""$$env.${name} = "${escapeValue(value)}""""

    def envVarPattern(name: String): scala.util.matching.Regex =
      s"""(?m)^\\$$env\\.${java.util.regex.Pattern.quote(name)} = "[^"\n]*"$$""".r
  }

  case object PowerShell extends ShellConfiguration {
    val name = "PowerShell"
    val binaryNames = Seq("pwsh", "powershell")
    val configPathDetectionArgs = Seq("-c", "echo $PROFILE")

    override def formatComment(text: String): String = s"# $text"

    protected def escapeValue(value: String): String =
      value
        .replace("`", "``")
        .replace("\"", "`\"")
        .replace("\n", "`n")

    def formatEnvVar(name: String, value: String): String =
      s"""$$env:${name} = "${escapeValue(value)}""""

    def envVarPattern(name: String): scala.util.matching.Regex =
      s"""(?m)^\\$$env:${java.util.regex.Pattern.quote(name)} = "[^"\n]*"$$""".r
  }
}
