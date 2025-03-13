package mill.main.buildgen

import mill.main.buildgen.BasicReadWriters.*
import upickle.default.{macroRW, ReadWriter as RW}

case class IrLicense(
    id: String,
    name: String,
    url: String,
    isOsiApproved: Boolean = false,
    isFsfLibre: Boolean = false,
    distribution: String = "repo"
)
object IrLicense {
  implicit val rw: RW[IrLicense] = macroRW
}

case class IrVersionControl(
    url: Option[String] = None,
    connection: Option[String] = None,
    developerConnection: Option[String] = None,
    tag: Option[String] = None
)
object IrVersionControl {
  implicit val rw: RW[IrVersionControl] = macroRW
}

case class IrDeveloper(
    id: String,
    name: String,
    url: String,
    organization: Option[String] = None,
    organizationUrl: Option[String] = None
)
object IrDeveloper {
  implicit val rw: RW[IrDeveloper] = macroRW
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
  implicit val rw: RW[IrPomSettings] = macroRW
}

sealed trait IrCrossVersion
object IrCrossVersion {
  case class Constant(value: String = "", platformed: Boolean = false) extends IrCrossVersion
  object Constant {
    implicit def rw: RW[Constant] = macroRW
  }
  case class Binary(platformed: Boolean = false) extends IrCrossVersion
  object Binary {
    implicit def rw: RW[Binary] = macroRW
  }
  case class Full(platformed: Boolean = false) extends IrCrossVersion
  object Full {
    implicit def rw: RW[Full] = macroRW
  }

  implicit def rw: RW[IrCrossVersion] = RW.merge(Constant.rw, Binary.rw, Full.rw)
}

case class IrDep(
    organization: String,
    name: String,
    version: String,
    crossVersion: IrCrossVersion = IrCrossVersion.Constant(),
    `type`: Option[String] = None,
    classifier: Option[String] = None
)
object IrDep {

  implicit val rw: RW[IrDep] = macroRW
}

sealed trait IrModuleConfig
object IrModuleConfig {
  implicit val rw: RW[IrModuleConfig] = macroRW
}

case class IrCoursierModuleConfig(
    repositories: Seq[String] = Nil
) extends IrModuleConfig
object IrCoursierModuleConfig {
  implicit val rw: RW[IrCoursierModuleConfig] = macroRW
}

case class IrJavaModuleConfig(
    javacOptions: Seq[String] = Nil,
    sources: Seq[os.RelPath] = Nil,
    resources: Seq[os.RelPath] = Nil,
    bomIvyDeps: Seq[IrDep] = Nil,
    ivyDeps: Seq[IrDep] = Nil,
    moduleDeps: Seq[os.SubPath] = Nil,
    compileIvyDeps: Seq[IrDep] = Nil,
    compileModuleDeps: Seq[os.SubPath] = Nil,
    runIvyDeps: Seq[IrDep] = Nil,
    runModuleDeps: Seq[os.SubPath] = Nil
) extends IrModuleConfig
object IrJavaModuleConfig {
  implicit val rw: RW[IrJavaModuleConfig] = macroRW
}

case class IrPublishModuleConfig(
    pomSettings: IrPomSettings,
    publishVersion: String
) extends IrModuleConfig
object IrPublishModuleConfig {
  implicit val rw: RW[IrPublishModuleConfig] = macroRW
}

case class IrScalaModuleConfig(
    scalaVersion: String,
    scalacOptions: Seq[String]
) extends IrModuleConfig
object IrScalaModuleConfig {
  implicit val rw: RW[IrScalaModuleConfig] = macroRW
}

case class IrScalaJSModuleConfig(
    scalaJSVersion: String
) extends IrModuleConfig
object IrScalaJSModuleConfig {
  implicit val rw: RW[IrScalaJSModuleConfig] = macroRW
}

case class IrScalaNativeModuleConfig(
    scalaNativeVersion: String
) extends IrModuleConfig
object IrScalaNativeModuleConfig {
  implicit val rw: RW[IrScalaNativeModuleConfig] = macroRW
}
