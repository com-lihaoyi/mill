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
 * @note See https://ant.apache.org/ivy/history/latest-milestone/ivyfile/artifact.html for Ivy XML descriptions.
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
    extension = ext, config = ivyConfig, `type` = ivyType, classifier = classifier
  )
}

object PublishInfo {
  implicit def jsonify: upickle.default.ReadWriter[PublishInfo] = upickle.default.macroRW

  /**
   * @param extension see [[PublishInfo.ext]]
   * @param config `conf` attribute on `artifact` tag in ivy.xml. Comma separated list of public configurations in
   *               which this artifact is published. `*` wildcard can be used to designate all public configurations
   *               of this module.
   * @param `type` `type` attribute on `artifact` tag in ivy.xml. This will implicitly define the directory, the
   *               file will be published to (e.g. "jar" -> "jars").
   * @param classifier see [[PublishInfo.classifier]]
   */
  private[mill] case class IvyMetadata(
    extension: String, config: String, `type`: String, classifier: Option[String]
  )
  private[mill] object IvyMetadata {
    def pom: IvyMetadata = apply(extension = "pom", config = "pom", `type` = "pom", classifier = None)
    def jar: IvyMetadata = apply(extension = "jar", config = "compile", `type` = "jar", classifier = None)
    def sourcesJar: IvyMetadata =
      apply(extension = "jar", config = "compile", `type` = "src", classifier = Some("sources"))
    def docJar: IvyMetadata =
      apply(extension = "jar", config = "compile", `type` = "doc", classifier = Some("javadoc"))
  }

  private[mill] def jar(jar: PathRef): PublishInfo = {
    PublishInfo(jar, ivyConfig = "compile")
  }

  private[mill] def aar(aar: PathRef): PublishInfo = {
    PublishInfo(aar, ivyConfig = "compile", ext = "aar", ivyType = "aar")
  }

  private[mill] def sourcesJar(sourcesJar: PathRef): PublishInfo =
    PublishInfo(
      sourcesJar,
      ivyType = "src",
      classifier = Some("sources"),
      ivyConfig = "compile"
    )

  private[mill] def docJar(docJar: PathRef): PublishInfo =
    PublishInfo(
      docJar,
      ivyType = "doc",
      classifier = Some("javadoc"),
      ivyConfig = "compile"
    )
}
