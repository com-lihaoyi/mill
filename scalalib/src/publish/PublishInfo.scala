package mill.scalalib.publish

import mill.PathRef

/** An extra resource artifact to publish.
 *
 * @param file The artifact file
 * @param classifier An Optional classifier to be used when publishing the file
 * @param ext The extension that will be used publishing the file to the ivy repo
 * @param ivyConfig The ivy config to be used
 * @param ivyType The ivy type, this will implicitly define the directory, the file will be published to (e.g. "jar" -> "jars")
 */
case class PublishInfo(
  file: PathRef,
  classifier: Option[String] = None,
  ext: String = "jar",
  ivyConfig: String,
  ivyType: String = "jar"
) {
  val classifierPart : String = classifier.map(c => s"-$c").getOrElse("")
}

object PublishInfo {
  implicit def jsonify: upickle.default.ReadWriter[PublishInfo] = upickle.default.macroRW
}
