package mill.init.importer

import upickle.default.{ReadWriter, macroRW}

sealed trait CrossVersionIR
object CrossVersionIR {
  case class Constant(value: String = "", platformed: Boolean = false) extends CrossVersionIR
  object Constant {
    implicit val rw: ReadWriter[Constant] = macroRW
  }
  case class Binary(platformed: Boolean = false) extends CrossVersionIR
  object Binary {
    implicit val rw: ReadWriter[Binary] = macroRW
  }
  case class Full(platformed: Boolean = false) extends CrossVersionIR
  object Full {
    implicit val rw: ReadWriter[Full] = macroRW
  }
  implicit val rw: ReadWriter[CrossVersionIR] = macroRW
}

case class DepIR(
    organization: String,
    name: String,
    version: Option[String] = None,
    crossVersion: CrossVersionIR = CrossVersionIR.Constant(),
    `type`: Option[String] = None,
    classifier: Option[String] = None,
    exclusions: Seq[(String, String)] = Nil
)
object DepIR {
  implicit val rw: ReadWriter[DepIR] = macroRW
}

case class ArtifactIR(
    group: String,
    id: String,
    version: String
)
object ArtifactIR {
  implicit val rw: ReadWriter[ArtifactIR] = macroRW
}

case class LicenseIR(
    id: String,
    name: String,
    url: String,
    isOsiApproved: Option[Boolean] = None,
    isFsfLibre: Option[Boolean] = None,
    distribution: Option[String] = None
)
object LicenseIR {
  implicit val rw: ReadWriter[LicenseIR] = macroRW
}

case class VersionControlIR(
    browsableRepository: Option[String] = None,
    connection: Option[String] = None,
    developerConnection: Option[String] = None,
    tag: Option[String] = None
)
object VersionControlIR {
  implicit val rw: ReadWriter[VersionControlIR] = macroRW
}

case class DeveloperIR(
    id: String,
    name: String,
    url: String,
    organization: Option[String] = None,
    organizationUrl: Option[String] = None
)
object DeveloperIR {
  implicit val rw: ReadWriter[DeveloperIR] = macroRW
}

case class PomSettingsIR(
    description: Option[String] = None,
    organization: Option[String] = None,
    url: Option[String] = None,
    licenses: Seq[LicenseIR] = Nil,
    versionControl: VersionControlIR = VersionControlIR(),
    developers: Seq[DeveloperIR] = Nil
)
object PomSettingsIR {
  implicit val rw: ReadWriter[PomSettingsIR] = macroRW
}
