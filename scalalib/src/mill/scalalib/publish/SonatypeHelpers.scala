package mill.scalalib.publish

import mill.util.Jvm

import java.math.BigInteger
import java.security.MessageDigest

object SonatypeHelpers {
  // http://central.sonatype.org/pages/working-with-pgp-signatures.html#signing-a-file

  val USERNAME_ENV_VARIABLE_NAME = "SONATYPE_USERNAME"
  val PASSWORD_ENV_VARIABLE_NAME = "SONATYPE_PASSWORD"

  private[mill] def getArtifactMappings(
      isSigned: Boolean,
      gpgArgs: Seq[String],
      workspace: os.Path,
      env: Map[String, String],
      artifacts: Seq[(Seq[(os.Path, String)], Artifact)]
  ): Seq[(Artifact, Seq[(String, Array[Byte])])] = {
    for ((fileMapping0, artifact) <- artifacts) yield {
      val publishPath = Seq(
        artifact.group.replace(".", "/"),
        artifact.id,
        artifact.version
      ).mkString("/")
      val fileMapping = fileMapping0.map { case (file, name) => (file, publishPath + "/" + name) }

      val signedArtifacts =
        if (isSigned) fileMapping.map {
          case (file, name) =>
            gpgSigned(file = file, args = gpgArgs, workspace = workspace, env = env) -> s"$name.asc"
        }
        else Seq()

      artifact -> (fileMapping ++ signedArtifacts).flatMap {
        case (file, name) =>
          val content = os.read.bytes(file)

          Seq(
            name -> content,
            (name + ".md5") -> md5hex(content),
            (name + ".sha1") -> sha1hex(content)
          )
      }
    }
  }
  private def gpgSigned(
      file: os.Path,
      args: Seq[String],
      workspace: os.Path,
      env: Map[String, String]
  ): os.Path = {
    val fileName = file.toString
    val command = "gpg" +: args :+ fileName

    Jvm.runSubprocess(command, env, workspace)
    os.Path(fileName + ".asc")
  }

  private def md5hex(bytes: Array[Byte]): Array[Byte] =
    hexArray(md5.digest(bytes)).getBytes

  private def sha1hex(bytes: Array[Byte]): Array[Byte] =
    hexArray(sha1.digest(bytes)).getBytes

  private def md5 = MessageDigest.getInstance("md5")

  private def sha1 = MessageDigest.getInstance("sha1")

  private def hexArray(arr: Array[Byte]) =
    String.format("%0" + (arr.length << 1) + "x", new BigInteger(1, arr))
}
