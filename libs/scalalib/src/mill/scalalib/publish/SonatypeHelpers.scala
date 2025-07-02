package mill.scalalib.publish

import mill.scalalib.FileSetContents
import os.SubPath

import java.math.BigInteger
import java.security.MessageDigest

object SonatypeHelpers {
  // http://central.sonatype.org/pages/working-with-pgp-signatures.html#signing-a-file

  val USERNAME_ENV_VARIABLE_NAME = "MILL_SONATYPE_USERNAME"
  val PASSWORD_ENV_VARIABLE_NAME = "MILL_SONATYPE_PASSWORD"

  private[mill] def getArtifactMappings(
      isSigned: Boolean,
      gpgArgs: Seq[String],
      workspace: os.Path,
      env: Map[String, String],
      artifacts: Seq[(FileSetContents.Path, Artifact)]
  ): Seq[(artifact: Artifact, contents: FileSetContents.Bytes)] = {
    for ((fileMapping0, artifact) <- artifacts) yield {
      val publishPath = SubPath(artifact.group.replace(".", "/")) / artifact.id / artifact.version
      val fileMapping = fileMapping0.mapPaths(publishPath / _)

      val signedArtifacts =
        if (isSigned) fileMapping.map {
          case (name, file) =>
            val signatureFile = gpgSigned(file = file.path, args = gpgArgs, workspace = workspace, env = env)
            SubPath(s"$name.asc") -> FileSetContents.Contents.Path(signatureFile)
        }
        else FileSetContents.empty

      val allFiles = (fileMapping ++ signedArtifacts).flatMap { case (name, file) =>
        val content = file.readFromDisk()

        Map(
          name -> content,
          SubPath(s"$name.md5") -> FileSetContents.Contents.Bytes.fromArray(md5hex(content.bytesUnsafe)),
          SubPath(s"$name.sha1") -> FileSetContents.Contents.Bytes.fromArray(sha1hex(content.bytesUnsafe))
        )
      }

      artifact -> allFiles
    }
  }

  /** Signs a file with GPG.
   *
   * @return The path of the signature file.
   * */
  private def gpgSigned(
      file: os.Path,
      args: Seq[String],
      workspace: os.Path,
      env: Map[String, String]
  ): os.Path = {
    val fileName = file.toString
    val command = "gpg" +: args :+ fileName

    println(s"Running GPG: ${command.map(pprint.Util.literalize(_)).mkString(" ")}")

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
    String.format("%0" + (arr.length << 1) + "x", new BigInteger(1, arr))
}
