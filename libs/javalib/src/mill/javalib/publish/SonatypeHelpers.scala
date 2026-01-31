package mill.javalib.publish

import mill.javalib.api.PgpWorkerApi
import mill.javalib.internal.PublishModule.GpgArgs
import mill.javalib.internal.PublishModule

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
      env: Map[String, String],
      pgpWorker: PgpWorkerApi,
      artifacts: Seq[(Map[os.SubPath, os.Path], Artifact)]
  ): Seq[(artifact: Artifact, contents: Map[os.SubPath, Array[Byte]])] = {
    val signingConfig =
      if (isSigned) Some(PublishModule.resolveSigningConfig(env, gpgArgs)) else None
    for ((fileMapping0, artifact) <- artifacts) yield {
      val publishPath =
        os.SubPath(artifact.group.replace(".", "/")) / artifact.id / artifact.version
      val fileMapping = fileMapping0.map { case (name, contents) => publishPath / name -> contents }

      val signedArtifacts =
        signingConfig match {
          case Some(config) =>
            fileMapping.map { case (name, file) =>
              val signatureFile = pgpWorker.signDetached(
                file = file,
                secretKeyBase64 = config.secretKeyBase64,
                keyIdHint = config.keyIdHint,
                passphrase = config.passphrase
              )
              os.SubPath(s"$name.asc") -> signatureFile
            }
          case None => Map.empty
        }

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

  private[mill] def toSubPathMap(fileMapping: Seq[(os.Path, String)]): Map[os.SubPath, os.Path] =
    fileMapping.iterator.map { case (path, name) => os.SubPath(name) -> path }.toMap

  private[mill] def mapArtifacts(
      artifacts: (Seq[(os.Path, String)], Artifact)*
  ): Array[(Map[os.SubPath, os.Path], Artifact)] =
    artifacts.iterator.map { case (fileMapping, artifact) =>
      toSubPathMap(fileMapping) -> artifact
    }.toArray

  private[mill] def getArtifactMappings(
      isSigned: Boolean,
      gpgArgs: GpgArgs,
      env: Map[String, String],
      artifacts: Seq[(Map[os.SubPath, os.Path], Artifact)]
  ): Seq[(artifact: Artifact, contents: Map[os.SubPath, Array[Byte]])] = {
    val _ = env
    val gpgArgs0 = gpgArgs.asCommandArgs
    for ((fileMapping0, artifact) <- artifacts) yield {
      val publishPath =
        os.SubPath(artifact.group.replace(".", "/")) / artifact.id / artifact.version
      val fileMapping = fileMapping0.map { case (name, contents) => publishPath / name -> contents }

      val signedArtifacts =
        if (isSigned) {
          fileMapping.map { case (name, file) =>
            val signatureFile = signWithGpg(file, gpgArgs0)
            os.SubPath(s"$name.asc") -> signatureFile
          }
        } else Map.empty

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

  // signing is delegated to the PGP worker or legacy gpg CLI

  private def md5hex(bytes: Array[Byte]): Array[Byte] =
    hexArray(md5.digest(bytes)).getBytes

  private def sha1hex(bytes: Array[Byte]): Array[Byte] =
    hexArray(sha1.digest(bytes)).getBytes

  private def md5 = MessageDigest.getInstance("md5")

  private def sha1 = MessageDigest.getInstance("sha1")

  private def hexArray(arr: Array[Byte]) =
    String.format("%0" + (arr.length << 1) + "x", new BigInteger(1, arr))

  private def signWithGpg(file: os.Path, gpgArgs: Seq[String]): os.Path = {
    val signatureFile = os.temp(dir = file / os.up, prefix = file.last + "-", suffix = ".asc")
    val cmd = Seq("gpg") ++ gpgArgs ++ Seq("--output", signatureFile.toString, file.toString)
    os.proc(cmd).call(stdout = os.Pipe, stderr = os.Pipe)
    signatureFile
  }
}
