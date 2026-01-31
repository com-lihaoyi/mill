package mill.javalib.publish

import com.lumidion.sonatype.central.client.core.{DeploymentName, PublishingType as STPubType}
import com.lumidion.sonatype.central.client.requests.SyncSonatypeClient
import mill.api.Logger
import mill.javalib.api.PgpWorkerApi
import mill.javalib.internal.PublishModule.GpgArgs
import mill.javalib.internal.PublishModule

import java.math.BigInteger
import java.security.MessageDigest
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

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
    val signer = Option.when(isSigned) {
      val config = PublishModule.resolveSigningConfig(env, gpgArgs)
      (file: os.Path) =>
        pgpWorker.signDetached(
          file = file,
          secretKeyBase64 = config.secretKeyBase64,
          keyIdHint = config.keyIdHint,
          passphrase = config.passphrase
        )
    }
    buildArtifactMappings(artifacts, signer)
  }

  private[mill] def toSubPathMap(fileMapping: Seq[(os.Path, String)]): Map[os.SubPath, os.Path] =
    fileMapping.iterator.map { case (path, name) => os.SubPath(name) -> path }.toMap

  private[mill] def mapArtifacts(
      artifacts: (Seq[(os.Path, String)], Artifact)*
  ): Array[(Map[os.SubPath, os.Path], Artifact)] =
    artifacts.iterator.map { case (fileMapping, artifact) =>
      toSubPathMap(fileMapping) -> artifact
    }.toArray

  private def mappingsString(
      mappings: Seq[(artifact: Artifact, contents: Map[os.SubPath, Array[Byte]])]
  ): String = s"mappings ${pprint(
      mappings.map { case (a, fileSetContents) =>
        (a, fileSetContents.keys.toVector.sorted.map(_.toString))
      }
    )}"

  private[mill] def prepareToPublishAll(
      singleBundleName: Option[String],
      artifacts: Seq[(Map[os.SubPath, os.Path], Artifact)],
      mapArtifacts: Seq[(
          Map[os.SubPath, os.Path],
          Artifact
      )] => Seq[(artifact: Artifact, contents: Map[os.SubPath, Array[Byte]])],
      log: Logger
  ): (
      Seq[(artifact: Artifact, contents: Map[os.SubPath, Array[Byte]])],
      Vector[(zipFile: java.io.File, deploymentName: DeploymentName)]
  ) = {
    val releases = mapArtifacts(artifacts)

    val releaseGroups = releases.groupBy(_.artifact.group)
    val wd = os.pwd / "out/publish-central"
    os.makeDir.all(wd)

    singleBundleName match {
      case None =>
        val deployments = releaseGroups.valuesIterator.flatMap { groupReleases =>
          groupReleases.map { case (artifact, data) =>
            val fileNameWithoutExtension = s"${artifact.group}-${artifact.id}-${artifact.version}"
            val zipFile = streamToFile(fileNameWithoutExtension, wd) { outputStream =>
              log.info(
                s"bundle $fileNameWithoutExtension with ${pprint.apply(data.keys.toVector.sorted.map(_.toString))}"
              )
              zipFilesToJar(data, outputStream)
            }

            val deploymentName = DeploymentName.fromArtifact(
              artifact.group,
              artifact.id,
              artifact.version
            )
            (zipFile, deploymentName)
          }
        }.toVector

        (releases, deployments)

      case Some(singleBundleName) =>
        val zipFile = streamToFile(singleBundleName, wd) { outputStream =>
          for ((_, groupReleases) <- releaseGroups) {
            groupReleases.foreach { case (_, data) =>
              zipFilesToJar(data, outputStream)
            }
          }
        }

        val deploymentName = DeploymentName(singleBundleName)
        (releases, Vector((zipFile, deploymentName)))
    }
  }

  private[mill] def publishAll(
      singleBundleName: Option[String],
      artifacts: Seq[(Map[os.SubPath, os.Path], Artifact)],
      mapArtifacts: Seq[(
          Map[os.SubPath, os.Path],
          Artifact
      )] => Seq[(artifact: Artifact, contents: Map[os.SubPath, Array[Byte]])],
      log: Logger
  )(publishFile: (java.io.File, DeploymentName) => Unit): Unit = {
    val (mappings, deployments) =
      prepareToPublishAll(singleBundleName, artifacts, mapArtifacts, log)
    log.info(mappingsString(mappings))
    deployments.foreach { case (zipFile, deploymentName) =>
      publishFile(zipFile, deploymentName)
    }
  }

  private[mill] def publishAllToLocal(
      singleBundleName: Option[String],
      artifacts: Seq[(Map[os.SubPath, os.Path], Artifact)],
      mapArtifacts: Seq[(
          Map[os.SubPath, os.Path],
          Artifact
      )] => Seq[(artifact: Artifact, contents: Map[os.SubPath, Array[Byte]])],
      publishTo: os.Path,
      log: Logger
  ): Unit = {
    val (mappings, deployments) =
      prepareToPublishAll(singleBundleName, artifacts, mapArtifacts, log)
    log.info(mappingsString(mappings))
    deployments.foreach { case (zipFile, deploymentName) =>
      val target = publishTo / deploymentName.unapply
      log.info(s"Unzipping $zipFile to $target")
      os.makeDir.all(target)
      os.unzip(os.Path(zipFile.toPath), target)
    }
  }

  private[mill] def publishBundle(
      sonatypeCentralClient: SyncSonatypeClient,
      zipFile: java.io.File,
      deploymentName: DeploymentName,
      publishingType: PublishingType,
      awaitTimeout: Int,
      log: Logger
  ): Unit = {
    try {
      mill.util.Retry(
        count = 5,
        backoffMillis = 1000,
        filter = (_, ex) => ex.getMessage.contains("Read end dead")
      ) {
        val stPubType = publishingType match {
          case PublishingType.AUTOMATIC => STPubType.AUTOMATIC
          case PublishingType.USER_MANAGED => STPubType.USER_MANAGED
        }

        sonatypeCentralClient.uploadBundleFromFile(
          localBundlePath = zipFile,
          deploymentName = deploymentName,
          publishingType = Some(stPubType),
          timeout = awaitTimeout
        )
      }
    } catch {
      case ex: Throwable =>
        throw new RuntimeException(
          s"Failed to publish ${deploymentName.unapply} to Sonatype Central",
          ex
        )
    }

    log.info(s"Successfully published ${deploymentName.unapply} to Sonatype Central")
  }

  private[mill] def getArtifactMappings(
      isSigned: Boolean,
      gpgArgs: GpgArgs,
      env: Map[String, String],
      artifacts: Seq[(Map[os.SubPath, os.Path], Artifact)]
  ): Seq[(artifact: Artifact, contents: Map[os.SubPath, Array[Byte]])] = {
    val gpgArgs0 = gpgArgs.asCommandArgs
    val signer = Option.when(isSigned) { (file: os.Path) =>
      signWithGpg(file, gpgArgs0, env)
    }
    buildArtifactMappings(artifacts, signer)
  }

  // signing is delegated to the PGP worker or legacy gpg CLI

  private def md5hex(bytes: Array[Byte]): Array[Byte] =
    hexArray(md5.digest(bytes)).getBytes

  private def sha1hex(bytes: Array[Byte]): Array[Byte] =
    hexArray(sha1.digest(bytes)).getBytes

  private def buildArtifactMappings(
      artifacts: Seq[(Map[os.SubPath, os.Path], Artifact)],
      signer: Option[os.Path => os.Path]
  ): Seq[(artifact: Artifact, contents: Map[os.SubPath, Array[Byte]])] = {
    for ((fileMapping0, artifact) <- artifacts) yield {
      val publishPath =
        os.SubPath(artifact.group.replace(".", "/")) / artifact.id / artifact.version
      val fileMapping = fileMapping0.map { case (name, contents) => publishPath / name -> contents }

      val signedArtifacts = signer match {
        case Some(sign) =>
          fileMapping.map { case (name, file) =>
            val signatureFile = sign(file)
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

  private def md5 = MessageDigest.getInstance("md5")

  private def sha1 = MessageDigest.getInstance("sha1")

  private def hexArray(arr: Array[Byte]) =
    String.format("%0" + (arr.length << 1) + "x", new BigInteger(1, arr))

  private def signWithGpg(
      file: os.Path,
      gpgArgs: Seq[String],
      env: Map[String, String]
  ): os.Path = {
    val signatureFile = os.temp(dir = file / os.up, prefix = file.last + "-", suffix = ".asc")
    val cmd = Seq("gpg") ++ gpgArgs ++ Seq("--output", signatureFile.toString, file.toString)
    os.proc(cmd).call(stdout = os.Pipe, stderr = os.Pipe, env = env)
    signatureFile
  }

  private def streamToFile(
      fileNameWithoutExtension: String,
      wd: os.Path
  )(func: JarOutputStream => Unit): java.io.File = {
    val zipFile =
      (wd / s"$fileNameWithoutExtension.zip")
    val fileOutputStream = java.nio.file.Files.newOutputStream(zipFile.toNIO)
    val jarOutputStream = new JarOutputStream(fileOutputStream)
    try {
      func(jarOutputStream)
    } finally {
      jarOutputStream.close()
    }
    zipFile.toIO
  }

  private def zipFilesToJar(
      files: Map[os.SubPath, Array[Byte]],
      jarOutputStream: JarOutputStream
  ): Unit = {
    files.foreach { case (filename, fileAsBytes) =>
      val zipEntry = new ZipEntry(filename.toString)
      jarOutputStream.putNextEntry(zipEntry)
      jarOutputStream.write(fileAsBytes)
      jarOutputStream.closeEntry()
    }
  }
}
