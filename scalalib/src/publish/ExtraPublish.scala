package mill.scalalib.publish

import mill.PathRef

/** An extra resource artifact to publish.
 *
 * @param file The artifact file
 * @param ivyType The ivy type, this will implicitly define the directory, the file will be published to (e.g. "jar" -> "jars")
 *  @param ivyClassifier An Optional classifier to be used when publishing the file
 *  @param ivyConfig The ivy config to be used
 * @param ivyExt The extension that will be used publishing the file to the ivy repo
 */
case class ExtraPublish(
  file: PathRef,
  ivyType: String = "jar",
  ivyClassifier: Option[String] = None,
  ivyConfig: String = "compile",
  ivyExt: String = "jar"
) {
  val classifierPart : String = ivyClassifier.map(c => s"-$c").getOrElse("")
}

object ExtraPublish {
  implicit def jsonify: upickle.default.ReadWriter[ExtraPublish] = upickle.default.macroRW
}
