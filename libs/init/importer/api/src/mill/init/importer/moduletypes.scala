package mill.init.importer

import upickle.default.{ReadWriter, macroRW}

sealed trait ModuleIR
object ModuleIR {
  implicit val rw: ReadWriter[ModuleIR] = macroRW
}

case class CoursierModuleIR(
    repositories: Seq[String] = Seq(),
    checkGradleModules: Option[Boolean] = None
) extends ModuleIR
object CoursierModuleIR {
  implicit val rw: ReadWriter[CoursierModuleIR] = macroRW
}

case class JavaHomeModuleIR(
    jvmId: String
) extends ModuleIR
object JavaHomeModuleIR {
  implicit val rw: ReadWriter[JavaHomeModuleIR] = macroRW
}

case class RunModuleIR(
    forkArgs: Seq[String] = Nil,
    forkEnv: Map[String, String] = Map()
) extends ModuleIR
object RunModuleIR {
  implicit val rw: ReadWriter[RunModuleIR] = macroRW
}

case class JavaModuleIR(
    mandatoryMvnDeps: Seq[DepIR] = Nil,
    mvnDeps: Seq[DepIR] = Nil,
    compileMvnDeps: Seq[DepIR] = Nil,
    runMvnDeps: Seq[DepIR] = Nil,
    bomMvnDeps: Seq[DepIR] = Nil,
    artifactTypes: Seq[String] = Nil,
    javacOptions: Seq[String] = Nil,
    mandatoryJavacOptions: Seq[String] = Nil,
    moduleDeps: Seq[os.SubPath] = Nil,
    compileModuleDeps: Seq[os.SubPath] = Nil,
    runModuleDeps: Seq[os.SubPath] = Nil,
    bomModuleDeps: Seq[os.SubPath] = Nil,
    sources: Seq[os.SubPath] = Nil,
    resources: Seq[os.SubPath] = Nil,
    javadocOptions: Seq[String] = Nil,
    docResources: Seq[os.SubPath] = Nil,
    artifactName: Option[String] = None,
    artifactId: Option[String] = None
) extends ModuleIR
object JavaModuleIR {
  implicit val rw: ReadWriter[JavaModuleIR] = macroRW
}

case class PublishModuleIR(
    publishVersion: String,
    pomSettings: PomSettingsIR,
    pomPackagingType: Option[String] = None,
    pomParentProject: Option[ArtifactIR] = None,
    versionScheme: Option[String] = None,
    publishProperties: Map[String, String] = Map()
) extends ModuleIR
object PublishModuleIR {
  implicit val rw: ReadWriter[PublishModuleIR] = macroRW
}

case class ScalaModuleIR(
    scalaVersion: String,
    scalacPluginMvnDeps: Seq[DepIR] = Nil,
    scalaDocPluginMvnDeps: Seq[DepIR] = Nil,
    scalacOptions: Seq[String] = Nil,
    scalaDocOptions: Seq[String] = Nil,
    consoleScalacOptions: Seq[String] = Nil,
    crossFullScalaVersion: Option[Boolean] = None
) extends ModuleIR
object ScalaModuleIR {
  implicit val rw: ReadWriter[ScalaModuleIR] = macroRW
}

case class ScalaJSModuleIR( // TODO add more fields
    scalaJSVersion: String) extends ModuleIR
object ScalaJSModuleIR {
  implicit val rw: ReadWriter[ScalaJSModuleIR] = macroRW
}

case class ScalaNativeModuleIR( // TODO add more fields
    scalaNativeVersion: String) extends ModuleIR
object ScalaNativeModuleIR {
  implicit val rw: ReadWriter[ScalaNativeModuleIR] = macroRW
}
