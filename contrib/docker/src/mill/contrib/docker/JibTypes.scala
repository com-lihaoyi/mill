package mill
package contrib.docker

import upickle.default.*

enum JibImageFormat derives ReadWriter {
  case Docker, OCI
}

sealed trait JibCredentialConfig

object JibCredentialConfig {
  case class EnvironmentVariables(usernameEnv: String, passwordEnv: String)
      extends JibCredentialConfig
  object EnvironmentVariables {
    def apply(credentialsEnvironment: (String, String)): EnvironmentVariables =
      EnvironmentVariables(credentialsEnvironment._1, credentialsEnvironment._2)
  }
  case class DockerConfig(path: os.Path = os.home / ".docker" / "config.json")
      extends JibCredentialConfig
  case class DockerCredentialHelper(helper: String) extends JibCredentialConfig
  case class GoogleADC() extends JibCredentialConfig

  given ReadWriter[EnvironmentVariables] = macroRW
  given ReadWriter[DockerConfig] = macroRW
  given ReadWriter[DockerCredentialHelper] = macroRW
  given ReadWriter[GoogleADC] = macroRW
  given ReadWriter[JibCredentialConfig] = macroRW
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
      credentials: Seq[JibCredentialConfig] = Seq.empty
  ) extends JibTargetImage
      with JibSourceImage {
    def credentialsEnvironment: Option[(String, String)] =
      credentials.collectFirst {
        case JibCredentialConfig.EnvironmentVariables(usernameEnv, passwordEnv) =>
          (usernameEnv, passwordEnv)
      }
  }
  object DockerDaemonImage {
    def apply(
        qualifiedName: String,
        credentialsEnvironment: Option[(String, String)]
    ): DockerDaemonImage =
      DockerDaemonImage(
        qualifiedName,
        credentialsEnvironment.map(JibCredentialConfig.EnvironmentVariables(_)).toSeq
      )
  }
  case class RegistryImage(
      qualifiedName: String,
      credentials: Seq[JibCredentialConfig] = Seq.empty
  ) extends JibTargetImage
      with JibSourceImage {
    def credentialsEnvironment: Option[(String, String)] =
      credentials.collectFirst {
        case JibCredentialConfig.EnvironmentVariables(usernameEnv, passwordEnv) =>
          (usernameEnv, passwordEnv)
      }
  }
  object RegistryImage {
    def apply(
        qualifiedName: String,
        credentialsEnvironment: Option[(String, String)]
    ): RegistryImage =
      RegistryImage(
        qualifiedName,
        credentialsEnvironment.map(JibCredentialConfig.EnvironmentVariables(_)).toSeq
      )
  }

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
