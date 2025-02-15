package mill.main.sbt

import mill.main.sbt.BuildPublicationInfo.License
import mill.main.sbt.Models.URL
import sbt.Keys
import upickle.default.{macroRW, ReadWriter => RW}

object Models {
  type URL = String

  // TODO Remove. This seems not supported.

  type Id[X] = X // not working with uPickle
  /*object Id {
    //implicit def rw[T](tRw : RW[T]) : RW[Id[T]] = tRw
    implicit def rw[T: RW]: RW[Id[T]] = macroRW
  }*/
}

// TODO Remove. Not used.
/**
 * A wrapper added because [[Models.Id]] does not work with uPickle.
 * @param wrappedValue named this way in order not to conflict with [[sbt.std.MacroValue.value]]
 */
case class Wrapper[+A](wrappedValue: A) extends AnyVal {
  def map[B](f: A => B): Wrapper[B] = Wrapper(f(wrappedValue))
}
object Wrapper {
  import scala.language.implicitConversions
  implicit def to[A](value: A): Wrapper[A] =
    Wrapper(value)
  implicit def from[A](wrapper: Wrapper[A]): A =
    wrapper.wrappedValue

  implicit def rw[T: RW]: RW[Wrapper[T]] = macroRW
}

case class BuildExport(
    /** @see [[sbt.AutoPlugin.buildSettings]] and [[sbt.AutoPlugin.globalSettings]] */
    defaultBuildInfo: BuildInfo,
    projects: Seq[Project]
)
object BuildExport {
  implicit val rw: RW[BuildExport] = macroRW
}

// TODO remove these plain ones

case class PlainBuildInfo(
    buildPublicationInfo: PlainBuildPublicationInfo,
    /** @see [[Keys.javacOptions]] */
    javacOptions: Seq[String],
    /** @see [[Keys.resolvers]] */
    resolvers: Seq[Resolver]
)
object PlainBuildInfo {
  implicit val rw: RW[PlainBuildInfo] = macroRW
}

case class PlainBuildPublicationInfo(
    /** @see [[Keys.description]] */
    description: String,
    /**
     * corresponds to `url` in POM
     *
     * @see [[Keys.homepage]]
     */
    homepage: Option[String],
    /** @see [[Keys.licenses]] */
    licenses: Seq[License],
    // organization: Option[String],
    /*
                                      /**
     * corresponds to Maven's `organization` in POM
     *
     * @see [[Keys.organization]]
     */
                                      organizationName: String,
     */
    /**
     * corresponds to `organizationUrl` in POM
     *
     * @see [[Keys.organizationHomepage]]
     */
    organizationHomepage: Option[String],
    /** @see [[Keys.developers]] */
    developers: Seq[Developer],
    /** @see [[Keys.scmInfo]] */
    scmInfo: Option[ScmInfo]
)
object PlainBuildPublicationInfo {
  implicit val rw: RW[PlainBuildPublicationInfo] = macroRW

  /** @see [[sbt.librarymanagement.License]] */
  type License = (String, URL)
}

/*
TODO I used higher-kinded types here along with some higher-rank types (`GetValueFn` and `MapFn`) in the sbt plugin,
 because I first thought that the default build info's members don't need to be wrapped with `Option` while the project build info's members do,
 and we can reuse some more code this way,
 but it turns out that both kinds have to be wrapped with `Option` and the abstraction doesn't work with the sbt `value` macros.
 Most likely we can just replace `F` with `Option` in them and remove the `TC` suffixes,
 resulting in `mill.main.sbt.BuildInfo` below,
 unless they can possibly provide more extensibility, which is unlikely as I think now.
 See other related definitions such as `Id`, `Wrapper`,
 `getBuildInfo[F[_]](ref: Reference, getValue: GetValueFn[F], map: MapFn[F]): BuildInfoTC[F]`, `GetValueFn`, and `MapFn`.
 */

case class BuildInfoTC[F[_]](
    buildPublicationInfo: BuildPublicationInfoTC[F],
    /** @see [[Keys.javacOptions]] */
    javacOptions: F[Seq[String]],
    /** @see [[Keys.scalacOptions]] */
    scalacOptions: F[Seq[String]],
    /** @see [[Keys.resolvers]] */
    resolvers: F[Seq[Resolver]]
)
object BuildInfoTC {
  type BuildInfo = BuildInfoTC[Wrapper]
  implicit val wrapperRw: RW[BuildInfo] = macroRW
  type OptionBuildInfo = BuildInfoTC[Option]
  implicit val optionRw: RW[OptionBuildInfo] = macroRW
}

case class BuildPublicationInfoTC[F[_]](
    /** @see [[Keys.description]] */
    description: F[String],
    /**
     * corresponds to `url` in POM
     *
     * @see [[Keys.homepage]]
     */
    homepage: F[Option[String]],
    /** @see [[Keys.licenses]] */
    licenses: F[Seq[PlainBuildPublicationInfo.License]],
    // organization: Option[String],
    /*
    /**
     * corresponds to Maven's `organization` in POM
     *
     * @see [[Keys.organization]]
     */
    organizationName: F[String],
     */
    /**
     * corresponds to `organizationUrl` in POM
     *
     * @see [[Keys.organizationHomepage]]
     */
    organizationHomepage: F[Option[String]],
    /** @see [[Keys.developers]] */
    developers: F[Seq[Developer]],
    /** @see [[Keys.scmInfo]] */
    scmInfo: F[Option[ScmInfo]]
)
object BuildPublicationInfoTC {

  /** @see [[sbt.librarymanagement.License]] */
  type License = (String, URL)

  /*
  // This does not work: "could not find implicit value for parameter e: upickle.default.Reader[F[String]]".
  implicit def rw[F[_]]: RW[BuildPublicationInfoTC[F]] = macroRW
   */
  /*
  // This does not work: "could not find implicit value for parameter e: upickle.default.Reader[mill.main.sbt.Models.Id[Seq[mill.main.sbt.BuildPublicationInfo.License]]]".
  type BuildPublicationInfo = BuildPublicationInfoTC[Id]
  implicit val IdRw: RW[BuildPublicationInfo] = macroRW
   */
  type BuildPublicationInfo = BuildPublicationInfoTC[Wrapper]
  implicit val wrapperRw: RW[BuildPublicationInfo] = macroRW
  type OptionBuildPublicationInfo = BuildPublicationInfoTC[Option]
  implicit val optionRw: RW[OptionBuildPublicationInfo] = macroRW
}

case class BuildInfo(
    buildPublicationInfo: BuildPublicationInfo,
    /** @see [[Keys.javacOptions]] */
    javacOptions: Option[Seq[String]],
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
    licenses: Option[Seq[PlainBuildPublicationInfo.License]],
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
    /**
     * corresponds to `organizationUrl` in POM
     *
     * @see [[Keys.organizationHomepage]]
     */
    organizationHomepage: Option[Option[String]],
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
    // dependencyConfigurations: Seq[DependencyConfiguration] // TODO remove
    allDependencies: Seq[Dependency]
)
object Project {
  implicit val rw: RW[Project] = macroRW

  // TODO Remove. Not used.
  type ProjectDirs = Seq[String]
}

// TODO Remove. For an old implementation of retrieving `Configuration / allDependencies` that doesn't work.
case class DependencyConfiguration(
    id: String,
    allDependencies: Seq[Dependency]
    /*, projectDependencies: Seq[ProjectDirs], libraryDependencies: Seq[LibraryDependency]*/
)
object DependencyConfiguration {
  implicit val rw: RW[DependencyConfiguration] = macroRW
}

case class Dependency(
    organization: String, // `groupId` in Maven
    name: String, // `artifactId` in Maven
    crossVersion: Boolean = false,
    revision: String,
    configurations: Option[String]
    // BOM seems not supported by sbt. See https://stackoverflow.com/questions/42032303/how-do-i-use-a-maven-bom-bill-of-materials-to-manage-my-dependencies-in-sbt.
    // isBom : Boolean = false
)
object Dependency {
  implicit val rw: RW[Dependency] = macroRW
}
