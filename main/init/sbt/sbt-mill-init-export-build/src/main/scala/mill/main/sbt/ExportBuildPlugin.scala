package mill.main.sbt

import sbt.Keys.*
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
    // not used anymore
    val millInitAllDependencies = taskKey[Seq[Dependency]]("get the all the `mill.main.sbt.Dependency`s of this project")
    val millInitProject = taskKey[Project]("get the `mill.main.sbt.Project` model of this project")
    val millInitExportBuild = taskKey[File]("export the build in a JSON file for `mill init`")
  }

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

  override lazy val projectSettings: Seq[Setting[?]] = Seq(
    buildInfoSetting,
    millInitProject :=
      Project(
        //organization.value,
        name.value,
        //version.value,
        //baseDirectory.value.relativeTo((ThisBuild / baseDirectory).value).get.getPath.split(File.separator),
        baseDirectory.value.getPath,
        /*{
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
        }*/
        millInitBuildInfo.value,

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
      ),
    // `target.value` doesn't work in `globalSettings` and `buildSettings`, so this is added to `projectSettings.
    millInitExportBuild := {
      val defaultBuildInfo = (ThisBuild / millInitBuildInfo).value
      val projects = millInitProject.all(ScopeFilter(inAnyProject)).value
      val buildExport = BuildExport(defaultBuildInfo, projects)

      val outputFile = target.value / "mill-init-build-export.json"
      IO.write(outputFile, write(buildExport))
      outputFile
    }
  )
}
