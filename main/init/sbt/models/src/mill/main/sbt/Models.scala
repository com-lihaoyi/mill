package mill.main.sbt

import mill.main.sbt.BuildPublicationInfo.License
import mill.main.sbt.Models.URL
import sbt.Keys
import upickle.default.{macroRW, ReadWriter => RW}

object Models {
  type URL = String
}

case class BuildExport(
    /** @see [[sbt.AutoPlugin.buildSettings]] and [[sbt.AutoPlugin.globalSettings]] */
    defaultBuildInfo: BuildInfo,
    projects: Seq[Project]
)
object BuildExport {
  implicit val rw: RW[BuildExport] = macroRW
}

case class BuildInfo(
    buildPublicationInfo: BuildPublicationInfo,
    /** @see [[Keys.javacOptions]] */
    javacOptions: Option[Seq[String]],
    /** @see [[Keys.scalaVersion]] */
    scalaVersion: Option[String],
    /** @see [[Keys.scalacOptions]] */
    scalacOptions: Option[Seq[String]],
    /** @see [[Keys.resolvers]] */
    resolvers: Option[Seq[Resolver]]
)
object BuildInfo {
  implicit val rw: RW[BuildInfo] = macroRW
}

/**
 * Members ordered by their order in [[Keys]].
 */
case class BuildPublicationInfo(
    /** @see [[Keys.description]] */
    description: Option[String],
    /**
     * corresponds to `url` in POM
     *
     * @see [[Keys.homepage]]
     */
    homepage: Option[Option[String]],
    /** @see [[Keys.licenses]] */
    licenses: Option[Seq[License]],
    /**
     * corresponds to `groupId` in POM and Mill's `PomSettings.organization`
     *
     * @see [[Keys.organization]]
     */
    organization: Option[String],
    // not needed
    /*
    /**
     * corresponds to Maven's `organization` in POM
     *
     * @see [[Keys.organizationName]]
     */
    organizationName: Option[String],
     */
    // not needed
    /*
    /**
     * corresponds to `organizationUrl` in POM
     *
     * @see [[Keys.organizationHomepage]]
     */
    organizationHomepage: Option[Option[String]],
     */
    /** @see [[Keys.developers]] */
    developers: Option[Seq[Developer]],
    /** @see [[Keys.scmInfo]] */
    scmInfo: Option[Option[ScmInfo]],
    /** @see [[Keys.version]] */
    version: Option[String]
)
object BuildPublicationInfo {

  /** @see [[sbt.librarymanagement.License]] */
  type License = (String, URL)

  implicit val rw: RW[BuildPublicationInfo] = macroRW
}

/**
 * @see [[sbt.librarymanagement.ScmInfo]]
 */
case class ScmInfo(
    browseUrl: URL,
    connection: String,
    devConnection: Option[String]
)
object ScmInfo {
  implicit val rw: RW[ScmInfo] = macroRW
}

/**
 * @see [[sbt.librarymanagement.Developer]]
 */
case class Developer(id: String, name: String, email: String, url: URL)
object Developer {
  implicit val rw: RW[Developer] = macroRW
}

/**
 * Only Maven repositories are supported now.
 * @see [[sbt.librarymanagement.Resolver]]
 */
case class Resolver(root: String)
object Resolver {
  implicit val rw: RW[Resolver] = macroRW
}

case class Project(
    // organization: String, // `groupId` in Maven, moved inside `buildInfo`
    name: String, // `artifactId` in Maven
    // version: String, // `groupId` in Maven, moved inside `buildInfo`
    // dirs: ProjectDirs, // relative
    projectDirectory: String,
    buildInfo: BuildInfo,
    allDependencies: Seq[Dependency]
)
object Project {
  implicit val rw: RW[Project] = macroRW
}

case class Dependency(
    organization: String, // `groupId` in Maven
    name: String, // `artifactId` in Maven
    crossVersion: CrossVersion = CrossVersion.Disabled,
    revision: String,
    configurations: Option[String]
    // BOM seems not supported by sbt. See https://stackoverflow.com/questions/42032303/how-do-i-use-a-maven-bom-bill-of-materials-to-manage-my-dependencies-in-sbt.
    // isBom : Boolean = false
)
object Dependency {
  implicit val rw: RW[Dependency] = macroRW
}

/**
 * @see [[sbt.librarymanagement.CrossVersion]]
 */
sealed trait CrossVersion
object CrossVersion {
  case object Disabled extends CrossVersion {
    implicit val rw: RW[Disabled.type] = macroRW
  }
  case object Binary extends CrossVersion {
    implicit val rw: RW[Binary.type] = macroRW
  }
  case object Full extends CrossVersion {
    implicit val rw: RW[Full.type] = macroRW
  }

  /**
   * Including the cases [[sbt.librarymanagement.Constant]], [[sbt.librarymanagement.For2_13Use3]], and [[sbt.librarymanagement.For3Use2_13]].
   */
  case class Constant(value: String) extends CrossVersion
  object Constant {
    implicit val rw: RW[Constant] = macroRW
  }

  implicit val rw: RW[CrossVersion] = macroRW
}
