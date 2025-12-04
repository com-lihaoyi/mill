package mill.javalib.internal

import mill.api.Task
import mill.api.daemon.internal.internal
import mill.javalib.publish.SonatypeHelpers.{PASSWORD_ENV_VARIABLE_NAME, USERNAME_ENV_VARIABLE_NAME}
import mill.util.{PossiblySecret, Secret}

@internal
private[mill] object PublishModule {

  /**
   * Imports a Base64 encoded GPG secret, if one is provided in the environment.
   *
   * @return Some(Right(the key ID of the imported secret)), Some(Left(error message)) if the import failed, None if
   *         the environment variable is not set.
   */
  def pgpImportSecretIfProvided(env: Map[String, String]): Option[Either[String, String]] = {
    for (secret <- env.get(EnvVarPgpSecretBase64)) yield {
      pgpImportSecret(secret).left.map { errorLines =>
        s"""Could not import PGP secret from environment variable '$EnvVarPgpSecretBase64'. gpg output:
           |
           |${errorLines.mkString("\n")}""".stripMargin
      }
    }
  }

  /** Imports a Base64 encoded GPG secret, if one is provided in the environment. Throws if the import fails. */
  def pgpImportSecretIfProvidedOrThrow(env: Map[String, String]): Option[String] =
    pgpImportSecretIfProvided(env).map(_.fold(
      err => throw IllegalArgumentException(err),
      identity
    ))

  /**
   * Imports a Base64 encoded GPG secret.
   *
   * @return Right(the key ID of the imported secret), or Left(gnupg output) if the import failed.
   */
  def pgpImportSecret(secretBase64: String): Either[Vector[String], String] = {
    val cmd = Seq(
      "gpg",
      "--import",
      "--no-tty",
      "--batch",
      "--yes",
      // Use the machine parseable output format and send it to stdout.
      "--with-colons",
      "--status-fd",
      "1"
    )
    println(s"Running ${cmd.iterator.map(pprint.Util.literalize(_)).mkString(" ")}")
    val res = os.call(cmd, stdin = java.util.Base64.getDecoder.decode(secretBase64))
    val outLines = res.out.lines()
    val importRegex = """^\[GNUPG:\] IMPORT_OK \d+ (\w+)""".r
    outLines.collectFirst { case importRegex(key) => key }.toRight(outLines)
  }

  def defaultGpgArgs: Seq[String] = Seq(
    "--no-tty",
    "--pinentry-mode",
    "loopback",
    "--batch",
    "--yes",
    "--armor",
    "--detach-sign"
  )

  def defaultGpgArgsForKey(key: Option[GpgKey]): Seq[PossiblySecret[String]] =
    key.iterator.flatMap(_.gpgArgs).toSeq ++ defaultGpgArgs

  def defaultGpgArgsForPassphrase(passphrase: Option[String]): Seq[PossiblySecret[String]] =
    GpgKey.gpgArgsForPassphrase(passphrase) ++ defaultGpgArgs

  def pgpImportSecretIfProvidedAndMakeGpgArgs(
      env: Map[String, String],
      providedGpgArgs: GpgArgs.UserProvided
  ): GpgArgs = {
    val maybeKeyId = pgpImportSecretIfProvidedOrThrow(env)
    println(maybeKeyId match {
      case Some(keyId) => s"Imported GPG key with ID '$keyId'"
      case None => "No GPG key was imported."
    })
    makeGpgArgs(env, maybeKeyId, providedGpgArgs)
  }

  def makeGpgArgs(
      env: Map[String, String],
      maybeKeyId: Option[String],
      providedGpgArgs: GpgArgs.UserProvided
  ): GpgArgs = {
    if (providedGpgArgs.args.nonEmpty) providedGpgArgs
    else {
      val maybePassphrase = GpgKey.createFromEnvVarsOrThrow(
        maybeKeyId = maybeKeyId,
        maybePassphrase = env.get(EnvVarPgpPassphrase)
      )
      GpgArgs.MillGenerated(defaultGpgArgsForKey(maybePassphrase))
    }
  }

  val EnvVarPgpPassphrase = "MILL_PGP_PASSPHRASE"
  val EnvVarPgpSecretBase64 = "MILL_PGP_SECRET_BASE64"

  case class GpgKey private (keyId: String, passphrase: Option[String]) {
    def gpgArgs: Seq[PossiblySecret[String]] =
      Seq("--local-user", keyId) ++ GpgKey.gpgArgsForPassphrase(passphrase)
  }

  object GpgKey {

    /** Creates an instance if the passphrase is not empty. */
    def apply(keyId: String, passphrase: Option[String]): GpgKey =
      GpgKey(keyId = keyId, passphrase = passphrase.filter(_.nonEmpty))

    /** Creates an instance if the passphrase is not empty. */
    def apply(keyId: String, passphrase: String): GpgKey =
      GpgKey(keyId = keyId, passphrase = if (passphrase.isEmpty) None else Some(passphrase))

    /**
     * @param maybeKeyId      will be [[None]] if the PGP key was not provided in the environment.
     * @param maybePassphrase will be [[None]] if the PGP passphrase was not provided in the environment.
     */
    def createFromEnvVars(
        maybeKeyId: Option[String],
        maybePassphrase: Option[String]
    ): Option[Either[String, GpgKey]] =
      (maybeKeyId, maybePassphrase) match {
        case (None, None) => None
        case (Some(keyId), maybePassphrase) => Some(Right(apply(keyId = keyId, maybePassphrase)))
        // If passphrase is provided, key is required.
        case (None, Some(_)) =>
          Some(Left("A passphrase was provided, but key was not successfully imported."))
      }

    def createFromEnvVarsOrThrow(
        maybeKeyId: Option[String],
        maybePassphrase: Option[String]
    ): Option[GpgKey] =
      createFromEnvVars(maybeKeyId, maybePassphrase)
        .map(_.fold(err => throw IllegalArgumentException(err), identity))

    def gpgArgsForPassphrase(passphrase: Option[String]): Seq[PossiblySecret[String]] =
      passphrase.iterator.flatMap(p => Iterator("--passphrase", Secret(p))).toSeq
  }

  enum GpgArgs {

    /**
     * When user provides the args himself, we can not log them because we do not know which ones are sensitive
     * information like a key passphrase.
     */
    case UserProvided(args: Seq[String])(using val file: sourcecode.File, val line: sourcecode.Line)

    /** When we generate the args ourselves we know which ones are secret. */
    case MillGenerated(args: Seq[PossiblySecret[String]])

    /** Turns this into the `gpg` arguments. */
    def asCommandArgs: Seq[String] = this match {
      case GpgArgs.UserProvided(args) => args
      case GpgArgs.MillGenerated(args) => args.iterator.map(Secret.unpack).toSeq
    }
  }

  object GpgArgs {

    /**
     * @param args a comma separated string, for example "--yes,--batch"
     */
    def fromUserProvided(args: String)(using sourcecode.File, sourcecode.Line): UserProvided =
      UserProvided(if (args.isBlank) Seq.empty else args.split(','))
  }

  def getSonatypeCredsFromEnv: Task[(String, String)] = Task.Anon {
    (for {
      // Allow legacy environment variables as well
      username <- Task.env.get(USERNAME_ENV_VARIABLE_NAME).orElse(Task.env.get("SONATYPE_USERNAME"))
      password <- Task.env.get(PASSWORD_ENV_VARIABLE_NAME).orElse(Task.env.get("SONATYPE_PASSWORD"))
    } yield {
      (username, password)
    }).getOrElse(
      Task.fail(
        s"Consider using ${USERNAME_ENV_VARIABLE_NAME}/${PASSWORD_ENV_VARIABLE_NAME} environment variables or passing `sonatypeCreds` argument"
      )
    )
  }

  def checkSonatypeCreds(sonatypeCreds: String): Task[String] =
    if (sonatypeCreds.isEmpty) {
      for {
        (username, password) <- getSonatypeCredsFromEnv
      } yield s"$username:$password"
    } else {
      Task.Anon {
        if (sonatypeCreds.split(":").length >= 2) {
          sonatypeCreds
        } else {
          Task.fail(
            "Sonatype credentials must be set in the following format - username:password. Incorrect format received."
          )
        }
      }
    }
}
