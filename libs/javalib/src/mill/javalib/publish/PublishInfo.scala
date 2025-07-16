package mill.javalib.publish

import mill.PathRef

/**
 * An extra resource artifact to publish.
 *
 * @param file The artifact file
 * @param classifier An Optional classifier to be used when publishing the file
 * @param ext The extension that will be used publishing the file to the ivy repo
 * @param ivyConfig see [[PublishInfo.IvyMetadata.config]]
 * @param ivyType see [[PublishInfo.IvyMetadata.`type`]]
 */
case class PublishInfo(
    file: PathRef,
    classifier: Option[String] = None,
    ext: String = "jar",
    ivyConfig: String,
    ivyType: String = "jar"
) {
  val classifierPart: String = classifier.map(c => s"-$c").getOrElse("")

  private[mill] def toIvyMetadata: PublishInfo.IvyMetadata = PublishInfo.IvyMetadata(
    extension = ext,
    config = ivyConfig,
    `type` = ivyType,
    classifier = classifier
  )
}

object PublishInfo {
  implicit def jsonify: upickle.default.ReadWriter[PublishInfo] = upickle.default.macroRW

  /**
   * See https://ant.apache.org/ivy/history/latest-milestone/ivyfile/artifact.html for Ivy XML descriptions.
   *
   * @param extension `ext` attribute on `artifact` tag in ivy.xml. See [[PublishInfo.ext]]
   * @param config `conf` attribute on `artifact` tag in ivy.xml. Comma separated list of public configurations in
   *               which this artifact is published. `*` wildcard can be used to designate all public configurations
   *               of this module.
   * @param `type` `type` attribute on `artifact` tag in ivy.xml. This will implicitly define the directory, the
   *               file will be published to (e.g. "jar" -> "jars").
   * @param classifier see [[PublishInfo.classifier]]
   */
  private[mill] case class IvyMetadata(
      extension: String,
      config: String,
      `type`: String,
      classifier: Option[String]
  ) {
    def toPublishInfo(file: PathRef): PublishInfo =
      PublishInfo(
        file,
        classifier = classifier,
        ext = extension,
        ivyConfig = config,
        ivyType = `type`
      )
  }
  private[mill] object IvyMetadata {
    val Pom: IvyMetadata =
      apply(extension = "pom", config = "pom", `type` = "pom", classifier = None)
    val Jar: IvyMetadata =
      apply(extension = "jar", config = "compile", `type` = "jar", classifier = None)
    val Aar: IvyMetadata =
      apply(extension = "aar", config = "compile", `type` = "aar", classifier = None)
    val SourcesJar: IvyMetadata =
      apply(extension = "jar", config = "compile", `type` = "src", classifier = Some("sources"))
    val DocJar: IvyMetadata =
      apply(extension = "jar", config = "compile", `type` = "doc", classifier = Some("javadoc"))

    val Known: IArray[IvyMetadata] = IArray(Pom, Jar, Aar, SourcesJar, DocJar)

    def tryToMatch(extension: String, classifier: Option[String]): Option[IvyMetadata] = {
      Known.find(meta => meta.extension == extension && meta.classifier == classifier)
    }
  }

  private[mill] def fromMetadata(file: PathRef, metadata: IvyMetadata): PublishInfo =
    metadata.toPublishInfo(file)
  private[mill] def pom(pom: PathRef): PublishInfo = fromMetadata(pom, IvyMetadata.Pom)
  private[mill] def jar(jar: PathRef): PublishInfo = fromMetadata(jar, IvyMetadata.Jar)
  private[mill] def aar(aar: PathRef): PublishInfo = fromMetadata(aar, IvyMetadata.Aar)
  private[mill] def sourcesJar(sourcesJar: PathRef): PublishInfo =
    fromMetadata(sourcesJar, IvyMetadata.SourcesJar)
  private[mill] def docJar(docJar: PathRef): PublishInfo = fromMetadata(docJar, IvyMetadata.DocJar)

  /**
   * Tries to parse the data from a filename.
   *
   * Take note that this is not perfect. After smushing the data into a string there's no way to tell what is the
   * classifier and what is the extension, as both of them can contain dots ('.'). The heuristic that we take is that
   * extensions are without the dot, so things like ".tar.gz" aren't supported, but they do not seem to be used in Maven
   * ecosystem, so that works out for most of the cases.
   *
   * This should eventually be rehauled, see https://github.com/com-lihaoyi/mill/issues/5538 for more information.
   *
   * @param file reference to the file.
   * @param fileName name of the file to use. Can be different from the actual filename of the `file`.
   * @param artifactId for example, "mill"
   * @param artifactVersion version of the artifact, for example "1.0.0-RC3"
   */
  private[mill] def parseFromFile(
      file: PathRef,
      fileName: String,
      artifactId: String,
      artifactVersion: String
  ): PublishInfo = {
    val withoutArtifactIdAndVersion = fileName.replaceFirst(s"^$artifactId-$artifactVersion-?", "")
    val extension = withoutArtifactIdAndVersion.split('.').lastOption.getOrElse("")
    val classifier = withoutArtifactIdAndVersion.replaceFirst(s"\\.$extension$$", "") match {
      case "" => None
      case other => Some(other)
    }

    IvyMetadata.tryToMatch(extension = extension, classifier = classifier) match {
      case Some(meta) => meta.toPublishInfo(file)
      case None =>
        // If nothing specific matched, assume it's a generic jar.
        val jar = IvyMetadata.Jar
        apply(
          file,
          classifier = classifier,
          ext = extension,
          ivyConfig = jar.config,
          ivyType = jar.`type`
        )
    }
  }
}
