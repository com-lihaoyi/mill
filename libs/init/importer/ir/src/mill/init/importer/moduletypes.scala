package mill.init.importer

import upickle.default._

sealed trait IrModule
object IrModule {
  implicit val rw: ReadWriter[IrModule] = macroRW
}

case class IrCoursierModule(
    repositories: Seq[String],
    checkGradleModules: Option[Boolean] = None
) extends IrModule
object IrCoursierModule {
  implicit val rw: ReadWriter[IrCoursierModule] = macroRW
}

case class IrJavaHomeModule(
    jvmId: String
) extends IrModule
object IrJavaHomeModule {
  implicit val rw: ReadWriter[IrJavaHomeModule] = macroRW
}

case class IrRunModule(
    forkArgs: Seq[String] = Nil,
    forkEnv: Map[String, String] = Map(),
    mainClass: Option[String] = None
) extends IrModule
object IrRunModule {
  implicit val rw: ReadWriter[IrRunModule] = macroRW
}

case class IrJavaModule(
    mandatoryMvnDeps: Seq[IrDep] = Nil,
    mvnDeps: Seq[IrDep] = Nil,
    compileMvnDeps: Seq[IrDep] = Nil,
    runMvnDeps: Seq[IrDep] = Nil,
    bomMvnDeps: Seq[IrDep] = Nil,
    artifactTypes: Seq[String] = Nil,
    javacOptions: Seq[String] = Nil,
    mandatoryJavacOptions: Seq[String] = Nil,
    moduleDeps: Seq[Seq[String]] = Nil,
    compileModuleDeps: Seq[Seq[String]] = Nil,
    runModuleDeps: Seq[Seq[String]] = Nil,
    bomModuleDeps: Seq[Seq[String]] = Nil,
    sourcesFolders: Seq[Seq[String]] = Nil,
    sources: Seq[Seq[String]] = Nil,
    resources: Seq[Seq[String]] = Nil,
    compileResources: Seq[Seq[String]] = Nil,
    javadocOptions: Seq[String] = Nil,
    artifactName: Option[String] = None,
    artifactId: Option[String] = None
) extends IrModule
object IrJavaModule {
  implicit val rw: ReadWriter[IrJavaModule] = macroRW
}

case class IrPublishModule(
    publishVersion: String,
    pomSettings: IrPomSettings,
    pomPackagingType: Option[String] = None,
    pomParentProject: Option[IrArtifact] = None,
    versionScheme: Option[String] = None,
    artifactMetadata: Option[IrArtifact] = None,
    publishProperties: Map[String, String] = Map()
) extends IrModule
object IrPublishModule {
  implicit val rw: ReadWriter[IrPublishModule] = macroRW
}

case class IrScalaModule(
    scalaVersion: String,
    scalacPluginMvnDeps: Seq[IrDep] = Nil,
    scalaDocPluginMvnDeps: Seq[IrDep] = Nil,
    scalaDocOptions: Seq[String] = Nil,
    consoleScalacOptions: Seq[String] = Nil,
    crossFullScalaVersion: Option[Boolean] = None
) extends IrModule
object IrScalaModule {
  implicit val rw: ReadWriter[IrScalaModule] = macroRW
}

case class IrScalaJSModule(
    scalaJSVersion: String
) extends IrModule
object IrScalaJSModule {
  implicit val rw: ReadWriter[IrScalaJSModule] = macroRW
}

case class IrScalaNativeModule(
    scalaNativeVersion: String
) extends IrModule
object IrScalaNativeModule {
  implicit val rw: ReadWriter[IrScalaNativeModule] = macroRW
}
