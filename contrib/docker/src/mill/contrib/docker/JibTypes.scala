package mill
package contrib.docker

import upickle.default.*

enum JibImageFormat derives ReadWriter {
  case Docker, OCI
}

sealed trait JibSourceImage
sealed trait JibTargetImage {
  def qualifiedName: String
}

object JibImage {
  case class SourceTarFile(path: mill.api.PathRef) extends JibSourceImage
  case class TargetTarFile(
      qualifiedName: String,
      filename: String = "out.tar"
  ) extends JibTargetImage
  case class DockerDaemonImage(
      qualifiedName: String,
      credentialsEnvironment: Option[(String, String)] = None
  ) extends JibTargetImage
      with JibSourceImage
  case class RegistryImage(
      qualifiedName: String,
      credentialsEnvironment: Option[(String, String)] = None
  ) extends JibTargetImage
      with JibSourceImage

  given ReadWriter[SourceTarFile] = macroRW
  given ReadWriter[TargetTarFile] = macroRW
  given ReadWriter[RegistryImage] = macroRW
  given ReadWriter[DockerDaemonImage] = macroRW
  given ReadWriter[JibTargetImage] = macroRW
  given ReadWriter[JibSourceImage] = macroRW
}

final case class JibPlatform(os: String, architecture: String)

object JibPlatform {
  given ReadWriter[JibPlatform] = macroRW
}
