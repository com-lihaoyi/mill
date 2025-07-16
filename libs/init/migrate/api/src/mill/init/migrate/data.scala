package mill.init.migrate

import upickle.default.{ReadWriter, macroRW}

case class ModuleDep(segments: Seq[String], argsByIndex: Map[Int, Seq[String]] = Map())
object ModuleDep {
  implicit val rw: ReadWriter[ModuleDep] = macroRW
}

sealed trait CrossVersionData
object CrossVersionData {
  case class Constant(value: String = "", platformed: Boolean = false) extends CrossVersionData
  object Constant {
    implicit val rw: ReadWriter[Constant] = macroRW
  }
  case class Binary(platformed: Boolean = false) extends CrossVersionData
  object Binary {
    implicit val rw: ReadWriter[Binary] = macroRW
  }
  case class Full(platformed: Boolean = false) extends CrossVersionData
  object Full {
    implicit val rw: ReadWriter[Full] = macroRW
  }
  implicit val rw: ReadWriter[CrossVersionData] = macroRW
}

case class DepData(
    organization: String,
    name: String,
    version: Option[String] = None,
    `type`: Option[String] = None,
    classifier: Option[String] = None,
    excludes: Seq[(String, String)] = Nil,
    crossVersion: CrossVersionData = CrossVersionData.Constant(),
    withDottyCompat: Boolean = false
)
object DepData {
  implicit val rw: ReadWriter[DepData] = macroRW
}

case class ArtifactData(
    group: String,
    id: String,
    version: String
)
object ArtifactData {
  implicit val rw: ReadWriter[ArtifactData] = macroRW
}

case class LicenseData(
    id: Option[String] = None,
    name: Option[String] = None,
    url: Option[String] = None,
    isOsiApproved: Option[Boolean] = None,
    isFsfLibre: Option[Boolean] = None,
    distribution: Option[String] = None
)
object LicenseData {
  implicit val rw: ReadWriter[LicenseData] = macroRW
}

case class VersionControlData(
    browsableRepository: Option[String] = None,
    connection: Option[String] = None,
    developerConnection: Option[String] = None,
    tag: Option[String] = None
)
object VersionControlData {
  implicit val rw: ReadWriter[VersionControlData] = macroRW
}

case class DeveloperData(
    id: Option[String] = None,
    name: Option[String] = None,
    url: Option[String] = None,
    organization: Option[String] = None,
    organizationUrl: Option[String] = None
)
object DeveloperData {
  implicit val rw: ReadWriter[DeveloperData] = macroRW
}

case class PomSettingsData(
    description: Option[String] = None,
    organization: Option[String] = None,
    url: Option[String] = None,
    licenses: Seq[LicenseData] = Nil,
    versionControl: VersionControlData = VersionControlData(),
    developers: Seq[DeveloperData] = Nil
)
object PomSettingsData {
  implicit val rw: ReadWriter[PomSettingsData] = macroRW
}

case class CoursierModuleData(
    repositories: Seq[String] = Nil
) {
  def base(that: CoursierModuleData) = CoursierModuleData(
    repositories.intersect(that.repositories)
  )
  def diff(that: CoursierModuleData) = CoursierModuleData(
    repositories.diff(that.repositories)
  )
}
object CoursierModuleData {
  implicit val rw: ReadWriter[CoursierModuleData] = macroRW
}

case class JavaHomeModuleData(
    jvmId: Option[String] = None
) {
  def base(that: JavaHomeModuleData) = JavaHomeModuleData(
    DataOps.base(jvmId, that.jvmId)
  )
  def diff(that: JavaHomeModuleData) = JavaHomeModuleData(
    DataOps.diff(jvmId, that.jvmId)
  )
}
object JavaHomeModuleData {
  implicit val rw: ReadWriter[JavaHomeModuleData] = macroRW
}

case class RunModuleData(
    forkArgs: Seq[String] = Nil,
    forkEnv: Map[String, String] = Map()
) {
  def base(that: RunModuleData) = RunModuleData(
    DataOps.baseSlice(forkArgs, that.forkArgs),
    DataOps.base(forkEnv, that.forkEnv)
  )
  def diff(that: RunModuleData) = RunModuleData(
    DataOps.diffSlice(forkArgs, that.forkArgs),
    DataOps.diff(forkEnv, that.forkEnv)
  )
}
object RunModuleData {
  implicit val rw: ReadWriter[RunModuleData] = macroRW
}

case class JavaModuleData(
    mvnDeps: Seq[DepData] = Nil,
    compileMvnDeps: Seq[DepData] = Nil,
    runMvnDeps: Seq[DepData] = Nil,
    bomMvnDeps: Seq[DepData] = Nil,
    artifactTypes: Seq[String] = Nil,
    javacOptions: Seq[String] = Nil,
    moduleDeps: Seq[ModuleDep] = Nil,
    compileModuleDeps: Seq[ModuleDep] = Nil,
    runModuleDeps: Seq[ModuleDep] = Nil,
    bomModuleDeps: Seq[ModuleDep] = Nil,
    sourceFolders: Seq[Seq[String]] = Nil,
    resources: Seq[Seq[String]] = Nil,
    javadocOptions: Seq[String] = Nil
) {
  def base(that: JavaModuleData) = JavaModuleData(
    mvnDeps.intersect(that.mvnDeps),
    compileMvnDeps.intersect(that.compileMvnDeps),
    runMvnDeps.intersect(that.runMvnDeps),
    bomMvnDeps.intersect(that.bomMvnDeps),
    artifactTypes.intersect(that.artifactTypes),
    DataOps.baseSlice(javacOptions, that.javacOptions),
    sourceFolders = sourceFolders.intersect(that.sourceFolders),
    resources = resources.intersect(that.resources),
    javadocOptions = DataOps.baseSlice(javadocOptions, that.javadocOptions)
  )

  def diff(that: JavaModuleData) = JavaModuleData(
    mvnDeps.diff(that.mvnDeps),
    compileMvnDeps.diff(that.compileMvnDeps),
    runMvnDeps.diff(that.runMvnDeps),
    bomMvnDeps.diff(that.bomMvnDeps),
    artifactTypes.diff(that.artifactTypes),
    DataOps.diffSlice(javacOptions, that.javacOptions),
    moduleDeps.diff(that.moduleDeps),
    compileModuleDeps.diff(that.compileModuleDeps),
    runModuleDeps.diff(that.runModuleDeps),
    bomModuleDeps.diff(that.bomModuleDeps),
    sourceFolders.diff(that.sourceFolders),
    resources.diff(that.resources),
    DataOps.diffSlice(javadocOptions, that.javadocOptions)
  )
}
object JavaModuleData {
  implicit val rw: ReadWriter[JavaModuleData] = macroRW
}

case class PublishModuleData(
    pomPackagingType: Option[String] = None,
    versionScheme: Option[String] = None,
    pomParentProject: Option[ArtifactData] = None,
    pomSettings: Option[PomSettingsData] = None,
    publishVersion: Option[String] = None,
    publishProperties: Map[String, String] = Map()
) {
  def base(that: PublishModuleData) = PublishModuleData(
    DataOps.base(pomPackagingType, that.pomPackagingType),
    DataOps.base(versionScheme, that.versionScheme),
    DataOps.base(pomParentProject, that.pomParentProject),
    DataOps.base(pomSettings, that.pomSettings),
    DataOps.base(publishVersion, that.publishVersion),
    DataOps.base(publishProperties, that.publishProperties)
  )

  def diff(that: PublishModuleData) = PublishModuleData(
    DataOps.diff(pomPackagingType, that.pomPackagingType),
    DataOps.diff(versionScheme, that.versionScheme),
    DataOps.diff(pomParentProject, that.pomParentProject),
    DataOps.diff(pomSettings, that.pomSettings),
    DataOps.diff(publishVersion, that.publishVersion),
    DataOps.diff(publishProperties, that.publishProperties)
  )
}
object PublishModuleData {
  implicit val rw: ReadWriter[PublishModuleData] = macroRW
}

case class ScalaModuleData(
    scalaVersion: Option[String] = None,
    scalacOptions: Seq[String] = Nil,
    scalaDocOptions: Seq[String] = Nil
) {
  def base(that: ScalaModuleData) = ScalaModuleData(
    DataOps.base(scalaVersion, that.scalaVersion),
    DataOps.baseSlice(scalacOptions, that.scalacOptions),
    DataOps.baseSlice(scalaDocOptions, that.scalaDocOptions)
  )
  def diff(that: ScalaModuleData) = ScalaModuleData(
    DataOps.diff(scalaVersion, that.scalaVersion),
    DataOps.diffSlice(scalacOptions, that.scalacOptions),
    DataOps.diffSlice(scalaDocOptions, that.scalaDocOptions)
  )
}
object ScalaModuleData {
  implicit val rw: ReadWriter[ScalaModuleData] = macroRW
}

case class ScalaJSModuleData(
    scalaJSVersion: Option[String] = None
) {
  def base(that: ScalaJSModuleData) = ScalaJSModuleData(
    DataOps.base(scalaJSVersion, that.scalaJSVersion)
  )
  def diff(that: ScalaJSModuleData) = ScalaJSModuleData(
    DataOps.diff(scalaJSVersion, that.scalaJSVersion)
  )
}
object ScalaJSModuleData {
  implicit val rw: ReadWriter[ScalaJSModuleData] = macroRW
}

case class ScalaNativeModuleData(
    scalaNativeVersion: Option[String] = None
) {
  def base(that: ScalaNativeModuleData) = ScalaNativeModuleData(
    DataOps.base(scalaNativeVersion, that.scalaNativeVersion)
  )
  def diff(that: ScalaNativeModuleData) = ScalaNativeModuleData(
    DataOps.diff(scalaNativeVersion, that.scalaNativeVersion)
  )
}
object ScalaNativeModuleData {
  implicit val rw: ReadWriter[ScalaNativeModuleData] = macroRW
}

case class ModuleData(
    coursierModule: Option[CoursierModuleData] = None,
    javaHomeModule: Option[JavaHomeModuleData] = None,
    runModule: Option[RunModuleData] = None,
    javaModule: Option[JavaModuleData] = None,
    publishModule: Option[PublishModuleData] = None,
    scalaModule: Option[ScalaModuleData] = None,
    scalaJSModule: Option[ScalaJSModuleData] = None,
    scalaNativeModule: Option[ScalaNativeModuleData] = None
) {
  def base(that: ModuleData) = ModuleData(
    DataOps.baseWith(coursierModule, that.coursierModule)(_.base(_)),
    DataOps.baseWith(javaHomeModule, that.javaHomeModule)(_.base(_)),
    DataOps.baseWith(runModule, that.runModule)(_.base(_)),
    DataOps.baseWith(javaModule, that.javaModule)(_.base(_)),
    DataOps.baseWith(publishModule, that.publishModule)(_.base(_)),
    DataOps.baseWith(scalaModule, that.scalaModule)(_.base(_)),
    DataOps.baseWith(scalaJSModule, that.scalaJSModule)(_.base(_)),
    DataOps.baseWith(scalaNativeModule, that.scalaNativeModule)(_.base(_))
  )
  def diff(that: ModuleData) = ModuleData(
    DataOps.diffWith(coursierModule, that.coursierModule)(_.diff(_)),
    DataOps.diffWith(javaHomeModule, that.javaHomeModule)(_.diff(_)),
    DataOps.diffWith(runModule, that.runModule)(_.diff(_)),
    DataOps.diffWith(javaModule, that.javaModule)(_.diff(_)),
    DataOps.diffWith(publishModule, that.publishModule)(_.diff(_)),
    DataOps.diffWith(scalaModule, that.scalaModule)(_.diff(_)),
    DataOps.diffWith(scalaJSModule, that.scalaJSModule)(_.diff(_)),
    DataOps.diffWith(scalaNativeModule, that.scalaNativeModule)(_.diff(_))
  )
  def nonEmpty = coursierModule.nonEmpty ||
    javaHomeModule.nonEmpty ||
    runModule.nonEmpty ||
    javaModule.nonEmpty ||
    publishModule.nonEmpty ||
    scalaModule.nonEmpty ||
    scalaJSModule.nonEmpty ||
    scalaNativeModule.nonEmpty
}
object ModuleData {
  implicit val rw: ReadWriter[ModuleData] = macroRW
}

object DataOps {

  def baseSlice[A](l: Seq[A], r: Seq[A]): Seq[A] =
    if (r.containsSlice(l)) l else if (l.containsSlice(r)) r else Nil

  def diffSlice[A](l: Seq[A], r: Seq[A]): Seq[A] = l.indexOfSlice(r) match {
    case -1 => l
    case i => l.take(i) ++ l.drop(i + r.length)
  }

  def base[K, V](l: Map[K, V], r: Map[K, V]): Map[K, V] =
    l.toSeq.intersect(r.toSeq).toMap

  def diff[K, V](l: Map[K, V], r: Map[K, V]): Map[K, V] =
    l.toSeq.diff(r.toSeq).toMap

  def base[A](l: Option[A], r: Option[A]): Option[A] =
    if (l == r) l else None

  def diff[A](l: Option[A], r: Option[A]): Option[A] =
    if (l == r) None else l

  def baseWith[A](l: Option[A], r: Option[A])(f: (A, A) => A): Option[A] =
    for {
      a1 <- l
      a2 <- r
    } yield f(a1, a2)

  def diffWith[A](l: Option[A], r: Option[A])(f: (A, A) => A): Option[A] =
    r.fold(l)(a2 => l.map(f(_, a2)))
}
