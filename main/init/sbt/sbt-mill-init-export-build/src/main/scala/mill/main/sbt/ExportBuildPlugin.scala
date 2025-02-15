package mill.main.sbt

import sbt.Keys.*
import sbt.Project.extract
import sbt.io.IO
import sbt.librarymanagement.Disabled
import sbt.{Def, Developer as _, Project as _, Resolver as _, ScmInfo as _, *}
import upickle.default.*

import java.io.File
import scala.language.higherKinds

object ExportBuildPlugin extends AutoPlugin {
  override def trigger = allRequirements
  // override def requires = ??? // defaults to `JvmPlugin`

  object autoImport {
    val millInitBuildInfo = taskKey[BuildInfo](
      "get the `mill.main.sbt.BuildInfo` model of this build or this project"
    )
    // TODO Remove. No longer used.
    val millInitDependencyConfiguration = taskKey[DependencyConfiguration](
      "get the `mill.main.sbt.DependencyConfiguration` model of this project and configuration"
    )
    val millInitAllDependencies = taskKey[Seq[Dependency]](
      "get the all the `mill.main.sbt.Dependency`s of this project"
    )
    val millInitProject = taskKey[Project]("get the `mill.main.sbt.Project` model of this project")
    val millInitExportBuild = taskKey[File]("export the build in a JSON file for `mill init`")
  }

  // TODO `getBuildInfo` along with `MapFn` and `GetValueFn` does not work with the `value` macro. Remove.

  trait MapFn[F[_]] {
    // def apply[A, B](fa : F[A], f: A => B): F[B] // Type inference doesn't work well with this.
    def apply[A, B](fa: F[A])(f: A => B): F[B]
  }

  trait GetValueFn[F[_]] {
    def apply[T](key: SettingKey[T]): F[T]

    def apply[T](key: TaskKey[T]): F[T]
  }

  def getBuildInfo[F[_]](ref: Reference, getValue: GetValueFn[F], map: MapFn[F]): BuildInfoTC[F] =
    BuildInfoTC(
      BuildPublicationInfoTC(
        getValue(ref / description),
        map(getValue(ref / homepage))(_.map(_.toExternalForm)),
        map(getValue(ref / licenses))(_.map { case (name, url) => (name, url.toExternalForm) }),
        //getValue(ref / organizationName), // not needed
        map(getValue(ref / organizationHomepage))(_.map(_.toExternalForm)),
        map(getValue(ref / developers))(_.map(developer =>
          Developer(developer.id, developer.name, developer.email, developer.url.toExternalForm)
        )),
        map(getValue(ref / scmInfo))(_.map(scmInfo =>
          ScmInfo(scmInfo.browseUrl.toExternalForm, scmInfo.connection, scmInfo.devConnection)
        ))
      ),
      getValue(ref / javacOptions),
      getValue(ref / scalacOptions),
      map(getValue(ref / resolvers))(_.flatMap {
        case mavenRepository: MavenRepository => Some(Resolver(mavenRepository.root))
        case resolver =>
          println(s"A `Resolver` which is not a `MavenRepository` is skipped: $resolver")
          None
      })
    )

  import autoImport.*

  val buildInfoSetting = millInitBuildInfo := BuildInfo(
    BuildPublicationInfo(
      description.?.value,
      homepage.?.value.map(_.map(_.toExternalForm)),
      licenses.?.value.map(_.map { case (name, url) =>
        (name, url.toExternalForm)
      }),
      organization.?.value,
      //organizationName.?.value, // not needed
      organizationHomepage.?.value.map(_.map(_.toExternalForm)),
      developers.?.value.map(_.map(developer =>
        Developer(developer.id, developer.name, developer.email, developer.url.toExternalForm)
      )),
      scmInfo.?.value.map(_.map(scmInfo =>
        ScmInfo(scmInfo.browseUrl.toExternalForm, scmInfo.connection, scmInfo.devConnection)
      )),
      version.?.value
    ),
    javacOptions.?.value,
    scalacOptions.?.value,
    resolvers.?.value.map(_.flatMap {
      case mavenRepository: MavenRepository => Some(Resolver(mavenRepository.root))
      case resolver =>
        println(s"A `Resolver` which is not a `MavenRepository` is skipped: $resolver")
        None
    })
  )

  override lazy val buildSettings: Seq[Def.Setting[?]] = Seq(
    buildInfoSetting
  )

  // TODO Remove. For an old implementation that depends on retrieving `configuration / allDependencies` which doesn't work.
  private val neededConfigurations = Seq(Compile, Test, Runtime, Default, Provided, Optional)

  // TODO Remove. No longer used. Replaced by `millInitAllDependencies`.
  val millInitDependencyConfigurationSetting =
    millInitDependencyConfiguration := DependencyConfiguration(
      configuration.value.id,

      /** See the TODO in [[sbt.Defaults]] above `allDependencies :=` for more details (v1.10.7, Lines 3210 - 3212). */
      /*allDependencies.value*/ (projectDependencies.value ++ libraryDependencies.value).map { moduleID =>
        Dependency(
          moduleID.organization,
          moduleID.name,
          moduleID.crossVersion match {
            case Disabled => false
            case _: Binary => true
            case crossVersion =>
              println(s"Unsupported `CrossVersion`: $crossVersion")
              false
          },
          moduleID.revision,
          moduleID.configurations
        )
      }
    )

  override lazy val projectSettings: Seq[Setting[?]] = Seq(
    Seq(buildInfoSetting),
    // TODO Remove. For an old implementation that depends on `configuration / allDependencies` which doesn't work.
    neededConfigurations.flatMap(configuration => inConfig(configuration)(Seq(millInitDependencyConfigurationSetting))),
    Seq(
      millInitAllDependencies := {
        /** See the TODO in [[sbt.Defaults]] above `allDependencies :=` for more details (v1.10.7, Lines 3210 - 3212). */
        /*allDependencies.value*/ (projectDependencies.value ++ libraryDependencies.value).map { moduleID =>
          Dependency(
            moduleID.organization,
            moduleID.name,
            moduleID.crossVersion match {
              case Disabled => false
              case _: Binary => true
              case crossVersion =>
                println(s"Unsupported `CrossVersion`: $crossVersion")
                false
            },
            moduleID.revision,
            moduleID.configurations
          )
        }
      },
      millInitProject := {
        // TODO remove
        val extracted = extract(state.value) // TODO move to a separate task if used
        Project(
          //organization.value,
          name.value,
          //version.value,
          //baseDirectory.value.relativeTo((ThisBuild / baseDirectory).value).get.getPath.split(File.separator),
          baseDirectory.value.getPath,
          // TODO remove old implementation
          /*
          getBuildInfo[Option](
            ThisProject,
            new GetValueFn[Option] {
              override def apply[T](key: SettingKey[T]): Option[T] = extracted.getOpt(key)

              override def apply[T](key: TaskKey[T]): Option[T] =
                ??? // extracted.getOpt(key).map(_.value)
            },
            new MapFn[Option] {
              override def apply[A, B](fa: Option[A])(f: A => B): Option[B] = fa.map(f)
            }
          )
           */
          {
            // keep the project `BuildInfo` members only when they are different
            val defaultBi = (ThisBuild / millInitBuildInfo).value
            val projectBi = millInitBuildInfo.value
            import projectBi.*
            BuildInfo({
              val defaultBpi = defaultBi.buildPublicationInfo
              import projectBi.buildPublicationInfo.*
              BuildPublicationInfo(
                if (description != defaultBpi.description) description else None,
                if (homepage != defaultBpi.homepage) homepage else None,
                if (licenses != defaultBpi.licenses) licenses else None,
                if (organization != defaultBpi.organization) organization else None,
                //if (organizationName != defaultBpi.organizationName) organizationName else None, // not needed
                if (organizationHomepage != defaultBpi.organizationHomepage) organizationHomepage else None,
                if (developers != defaultBpi.developers) developers else None,
                if (scmInfo != defaultBpi.scmInfo) scmInfo else None,
                if (version != defaultBpi.version) version else None
              )
            },
              if (javacOptions != defaultBi.javacOptions) javacOptions else None,
              if (scalacOptions != defaultBi.scalacOptions) scalacOptions else None,
              if (resolvers != defaultBi.resolvers) resolvers else None
            )
          },
          {
            // TODO Remove. For an old implementation that depends on retrieving `configuration / allDependencies` which doesn't work.
            millInitDependencyConfiguration.all(
              ScopeFilter(configurations = inConfigurations(neededConfigurations *))
            ).value
            millInitAllDependencies.value
          }
        )
      },
      // `target.value` doesn't work in `globalSettings` and `buildSettings`, so this is added to `projectSettings.
      millInitExportBuild := {
        // TODO This does not work with the `value` macro. Remove.
        /*
        val defaultBuildInfo = getBuildInfo[Wrapper](
          ThisBuild,
          new GetValueFn[Wrapper] {
            override def apply[T](key: SettingKey[T]): Wrapper[T] =
              ??? // Wrapper(key.value) // Could not find proxy
            override def apply[T](key: TaskKey[T]): Wrapper[T] =
              ??? // Wrapper(key.value) // Could not find proxy
          },
          new MapFn[Wrapper] {
            override def apply[A, B](fa: Wrapper[A])(f: A => B): Wrapper[B] = fa.map(f)
          }
        )
         */

        val defaultBuildInfo = (ThisBuild / millInitBuildInfo).value

        // TODO remove the old implementation code
        // `(projectRef / ...)` can't be used directly here due to macro limitations: "java.lang.IllegalArgumentException: Could not find proxy for projectRef: sbt.ProjectRef in ..."
        /*
        val allProjectPairs = buildStructure.value.allProjectPairs
        val projects = allProjectPairs.map { case (resolvedProject, projectRef) => ??? }
         */

        val projects = millInitProject.all(ScopeFilter(inAnyProject)).value

        val buildExport = BuildExport(defaultBuildInfo, projects)

        val outputFile = target.value / "mill-init-build-export.json"
        IO.write(outputFile, write(buildExport))
        outputFile
      }
    )).flatten
}
