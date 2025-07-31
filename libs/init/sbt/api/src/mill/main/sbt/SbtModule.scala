package mill.main.sbt

import mill.main.buildgen._
import upickle.default.{ReadWriter, macroRW}

case class SbtModule(
    moduleDir: Seq[String],
    baseDir: Seq[String],
    platformCrossType: Option[String],
    crossScalaVersions: Seq[String],
    module: ModuleRepr
)

object SbtModule {

  def apply(
      moduleDir: Seq[String],
      baseDir: Seq[String],
      platformCrossType: Option[String],
      crossScalaVersions: Seq[String],
      useVersionRanges: Boolean,
      mainConfigs: Seq[ModuleConfig],
      testModuleName: String,
      testModuleBase: Option[String],
      testConfigs: Seq[ModuleConfig]
  ): SbtModule = {
    val isCrossPlatform = moduleDir != baseDir
    val isCrossVersion = crossScalaVersions.length > 1
    val layoutType =
      if (isCrossPlatform && isCrossVersion) "CrossSbtPlatformModule"
      else if (isCrossPlatform) "SbtPlatformModule"
      else if (isCrossVersion) "CrossSbtModule"
      else "SbtModule"
    var main = if (isCrossPlatform) ModuleTypedef(baseDir.last, mainConfigs)
    else ModuleTypedef(configs = mainConfigs)
    main = main.extend(layoutType)
    if (isCrossVersion && useVersionRanges) main = main.extend("CrossScalaVersionRanges")
    val test = testModuleBase.map(testSupertype0 =>
      ModuleTypedef(
        testModuleName,
        ModuleTypedef.computeTestSupertypes(main.supertypes),
        testConfigs
      ).extend(testSupertypeByLayout(layoutType), testSupertype0)
    )
    SbtModule(
      moduleDir,
      baseDir,
      platformCrossType,
      crossScalaVersions,
      ModuleRepr(main, test)
    )
  }

  val testSupertypeByLayout = Map(
    "SbtModule" -> "SbtTests",
    "CrossSbtModule" -> "CrossSbtTests",
    "SbtPlatformModule" -> "SbtPlatformTests",
    "CrossSbtPlatformModule" -> "CrossSbtPlatformTests"
  )

  implicit val rw: ReadWriter[SbtModule] = macroRW
}
