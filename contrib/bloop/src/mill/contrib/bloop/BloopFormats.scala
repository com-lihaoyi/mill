//package mill.contrib.bloop
//
//import _root_.bloop.config.{Config => BloopConfig}
//import upickle.default.{ReadWriter, macroRW, readwriter}
//
//object BloopFormats {
//
//  implicit val pathRW: ReadWriter[java.nio.file.Path] =
//    readwriter[String].bimap[java.nio.file.Path](
//      _.toString,
//      java.nio.file.Paths.get(_)
//    )
//  implicit val artifactRW: ReadWriter[BloopConfig.Artifact] = macroRW
//  implicit val checksumRW: ReadWriter[BloopConfig.Checksum] = macroRW
//  implicit val linkerModeDebugRW: ReadWriter[BloopConfig.LinkerMode.Debug.type] = macroRW
//  implicit val linkerModeReleaseRW: ReadWriter[BloopConfig.LinkerMode.Release.type] = macroRW
//  implicit val moduleKindJSCommonJSModuleRW
//      : ReadWriter[BloopConfig.ModuleKindJS.CommonJSModule.type] = macroRW
//  implicit val moduleKindJSNoModuleRW: ReadWriter[BloopConfig.ModuleKindJS.NoModule.type] = macroRW
//  implicit val moduleKindJSESModuleRW: ReadWriter[BloopConfig.ModuleKindJS.ESModule.type] = macroRW
//  implicit val javaThenScalaRW: ReadWriter[BloopConfig.JavaThenScala.type] = macroRW
//  implicit val scalaThenJavaRW: ReadWriter[BloopConfig.ScalaThenJava.type] = macroRW
//  implicit val mixedRW: ReadWriter[BloopConfig.Mixed.type] = macroRW
//  implicit val compileOrderRW: ReadWriter[BloopConfig.CompileOrder] = macroRW
//  implicit val compileSetupRW: ReadWriter[BloopConfig.CompileSetup] = macroRW
//  implicit val fileRW: ReadWriter[BloopConfig.File] = macroRW
//  implicit val javaRW: ReadWriter[BloopConfig.Java] = macroRW
//  implicit val jsConfigRW: ReadWriter[BloopConfig.JsConfig] = macroRW
//  implicit val jvmConfigRW: ReadWriter[BloopConfig.JvmConfig] = macroRW
//  implicit val linkerModeRW: ReadWriter[BloopConfig.LinkerMode] = macroRW
//  implicit val moduleKindJSRW: ReadWriter[BloopConfig.ModuleKindJS] = macroRW
//  implicit val moduleRW: ReadWriter[BloopConfig.Module] = macroRW
//  implicit val nativeConfigRW: ReadWriter[BloopConfig.NativeConfig] = macroRW
//  implicit val nativeOptionsRW: ReadWriter[BloopConfig.NativeOptions] = macroRW
//  implicit val platformJsRW: ReadWriter[BloopConfig.Platform.Js] = macroRW
//  implicit val platformJvmRW: ReadWriter[BloopConfig.Platform.Jvm] = macroRW
//  implicit val platformNativeRW: ReadWriter[BloopConfig.Platform.Native] = macroRW
//  implicit val platformRW: ReadWriter[BloopConfig.Platform] = macroRW
//  implicit val resolutionRW: ReadWriter[BloopConfig.Resolution] = macroRW
//  implicit val sbtRW: ReadWriter[BloopConfig.Sbt] = macroRW
//  implicit val scalaRw: ReadWriter[BloopConfig.Scala] = macroRW
//  implicit val sourcesGlobsRW: ReadWriter[BloopConfig.SourcesGlobs] = macroRW
//  implicit val sourceGeneratorRW: ReadWriter[BloopConfig.SourceGenerator] = macroRW
//  implicit val testArgumentRW: ReadWriter[BloopConfig.TestArgument] = macroRW
//  implicit val testFrameworkRW: ReadWriter[BloopConfig.TestFramework] = macroRW
//  implicit val testOptionsRW: ReadWriter[BloopConfig.TestOptions] = macroRW
//  implicit val testRW: ReadWriter[BloopConfig.Test] = macroRW
//  implicit val projectRW: ReadWriter[BloopConfig.Project] = macroRW
//
//}
