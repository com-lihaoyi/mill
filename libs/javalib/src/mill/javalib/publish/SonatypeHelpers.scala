package mill.javalib.publish

import mill.javalib.internal.PublishModule.GpgArgs

import java.math.BigInteger
import java.security.MessageDigest

object SonatypeHelpers {
  // http://central.sonatype.org/pages/working-with-pgp-signatures.html#signing-a-file

  val CREDENTIALS_ENV_VARIABLE_PREFIX = "MILL_SONATYPE"
  val USERNAME_ENV_VARIABLE_NAME = s"${CREDENTIALS_ENV_VARIABLE_PREFIX}_USERNAME"
  val PASSWORD_ENV_VARIABLE_NAME = s"${CREDENTIALS_ENV_VARIABLE_PREFIX}_PASSWORD"

  private[mill] def getArtifactMappings(
      isSigned: Boolean,
      gpgArgs: GpgArgs,
      workspace: os.Path,
      env: Map[String, String],
      artifacts: Seq[(Map[os.SubPath, os.Path], Artifact)]
  ): Seq[(artifact: Artifact, contents: Map[os.SubPath, Array[Byte]])] = {
    for ((fileMapping0, artifact) <- artifacts) yield {
      val publishPath =
        os.SubPath(artifact.group.replace(".", "/")) / artifact.id / artifact.version
      val fileMapping = fileMapping0.map { case (name, contents) => publishPath / name -> contents }

      val signedArtifacts =
        if (isSigned) fileMapping.map {
          case (name, file) =>
            val signatureFile =
              gpgSigned(file = file, args = gpgArgs, workspace = workspace, env = env)
            os.SubPath(s"$name.asc") -> signatureFile
        }
        else Map.empty

      val allFiles = (fileMapping ++ signedArtifacts).flatMap { case (name, file) =>
        val content = os.read.bytes(file)

        Map(
          name -> content,
          os.SubPath(s"$name.md5") -> md5hex(content),
          os.SubPath(s"$name.sha1") -> sha1hex(content)
        )
      }

      artifact -> allFiles
    }
  }

  /**
   * Signs a file with GPG.
   *
   * @return The path of the signature file.
   */
  private def gpgSigned(
      file: os.Path,
      args: GpgArgs,
      workspace: os.Path,
      env: Map[String, String]
  ): os.Path = {
    val fileName = file.toString
    val logArgs = args match {
      case GpgArgs.MillGenerated(args) => args.map(_.toString)
      case fromUser: GpgArgs.UserProvided =>
        Seq(s"<${fromUser.args.size} user provided args @ ${fromUser.file}:${fromUser.line}>")
    }
    def mkCommand(args: Seq[String]) = "gpg" +: args :+ fileName
    mkCommand(logArgs)
    val command = mkCommand(args.asCommandArgs)

    os.call(
      command,
      env,
      workspace,
      stdin = os.Inherit,
      stdout = os.Inherit,
      stderr = os.Inherit
    )
    os.Path(fileName + ".asc")
  }

  private def md5hex(bytes: Array[Byte]): Array[Byte] =
    hexArray(md5.digest(bytes)).getBytes

  private def sha1hex(bytes: Array[Byte]): Array[Byte] =
    hexArray(sha1.digest(bytes)).getBytes

  private def md5 = MessageDigest.getInstance("md5")

  private def sha1 = MessageDigest.getInstance("sha1")

  private def hexArray(arr: Array[Byte]) =
    String.format("%0" + (arr.length << 1) + "x", BigInteger(1, arr))
}
