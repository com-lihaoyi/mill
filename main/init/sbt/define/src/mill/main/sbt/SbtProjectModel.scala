package mill.main.sbt

import mill.main.buildgen.*
import mill.main.buildgen.BasicReadWriters.*
import upickle.default.{macroRW, ReadWriter as RW}

case class SbtProjectModel(
    projectId: String,
    moduleName: String,
    baseDir: os.SubPath,
    crossVersions: Seq[String],
    crossPlatformBaseDir: Option[os.SubPath],
    coursierConfig: IrCoursierModuleConfig,
    javaConfig: IrJavaModuleConfig,
    publishConfig: Option[IrPublishModuleConfig],
    scalaConfig: IrScalaModuleConfig,
    scalaJSConfig: Option[IrScalaJSModuleConfig],
    scalaNativeConfig: Option[IrScalaNativeModuleConfig],
    testJavaConfig: Option[IrJavaModuleConfig]
)
object SbtProjectModel {
  implicit val rw: RW[SbtProjectModel] = macroRW
}
