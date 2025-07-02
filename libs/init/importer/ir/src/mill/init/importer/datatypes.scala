package mill.init.importer

import upickle.default._

sealed trait IrCrossVersion
object IrCrossVersion {
  case class Constant(value: String = "", platformed: Boolean = false) extends IrCrossVersion
  object Constant {
    implicit val rw: ReadWriter[Constant] = macroRW
  }
  case class Binary(platformed: Boolean = false) extends IrCrossVersion
  object Binary {
    implicit val rw: ReadWriter[Binary] = macroRW
  }
  case class Full(platformed: Boolean = false) extends IrCrossVersion
  object Full {
    implicit val rw: ReadWriter[Full] = macroRW
  }
  implicit val rw: ReadWriter[IrCrossVersion] = macroRW
}

case class IrDep(
    organization: String,
    name: String,
    version: Option[String] = None,
    crossVersion: IrCrossVersion = IrCrossVersion.Constant(),
    `type`: Option[String] = None,
    classifier: Option[String] = None,
    exclusions: Seq[(String, String)] = Nil
)
object IrDep {
  implicit val rw: ReadWriter[IrDep] = macroRW
}

case class IrArtifact(
    group: String,
    id: String,
    version: String
)
object IrArtifact {
  implicit val rw: ReadWriter[IrArtifact] = macroRW
}

case class IrLicense(
    id: String,
    name: String,
    url: String,
    isOsiApproved: Option[Boolean] = None,
    isFsfLibre: Option[Boolean] = None,
    distribution: Option[String] = None
)
object IrLicense {
  implicit val rw: ReadWriter[IrLicense] = macroRW
}

case class IrVersionControl(
    browsableRepository: Option[String] = None,
    connection: Option[String] = None,
    developerConnection: Option[String] = None,
    tag: Option[String] = None
)
object IrVersionControl {
  implicit val rw: ReadWriter[IrVersionControl] = macroRW
}

case class IrDeveloper(
    id: String,
    name: String,
    url: String,
    organization: Option[String] = None,
    organizationUrl: Option[String] = None
)
object IrDeveloper {
  implicit val rw: ReadWriter[IrDeveloper] = macroRW
}

case class IrPomSettings(
    description: String,
    organization: String,
    url: String,
    licenses: Seq[IrLicense] = Nil,
    versionControl: IrVersionControl = IrVersionControl(),
    developers: Seq[IrDeveloper] = Nil
)
object IrPomSettings {
  implicit val rw: ReadWriter[IrPomSettings] = macroRW
}
