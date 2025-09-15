package mill.main.sbt

import sbt.Keys._
import sbt.io.IO
import sbt.librarymanagement.{Binary, Disabled, Full, MavenRepository, _}
import sbt.{Def, CrossVersion => _, Developer => _, Project => _, Resolver => _, ScmInfo => _, _}
import upickle.default._

import java.io.File

object ExportBuildPlugin extends AutoPlugin {
  override def trigger = allRequirements
  // override def requires = ??? // defaults to `JvmPlugin`

  object autoImport {
    val millInitBuildInfo = taskKey[BuildInfo](
      "get the `mill.main.sbt.BuildInfo` model of this build or this project"
    )
    val millInitProject = taskKey[Project]("get the `mill.main.sbt.Project` model of this project")
    val millInitExportBuild = taskKey[File]("export the build in a JSON file for `mill init`")
  }

  import autoImport._

  val buildInfoSetting = millInitBuildInfo := BuildInfo(
    BuildPublicationInfo(
      description.?.value,
      homepage.?.value.map(_.map(_.toExternalForm)),
      licenses.?.value.map(_.map { case (name, url) =>
        (name, url.toExternalForm)
      }),
      organization.?.value,
      // organizationName.?.value, // not needed
      // organizationHomepage.?.value.map(_.map(_.toExternalForm)), // not needed
      developers.?.value.map(_.map(developer =>
        Developer(developer.id, developer.name, developer.email, developer.url.toExternalForm)
      )),
      scmInfo.?.value.map(_.map(scmInfo =>
        ScmInfo(scmInfo.browseUrl.toExternalForm, scmInfo.connection, scmInfo.devConnection)
      )),
      version.?.value
    ),
    javacOptions.?.value,
    scalaVersion.?.value,
    scalacOptions.?.value,
    resolvers.?.value.map(_.flatMap {
      case mavenRepository: MavenRepository => Some(Resolver(mavenRepository.root))
      case resolver =>
        println(s"A `Resolver` which is not a `MavenRepository` is skipped: $resolver")
        None
    })
  )

  override lazy val buildSettings: Seq[Def.Setting[_]] = Seq(
    buildInfoSetting
  )

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    buildInfoSetting,
    millInitProject :=
      Project(
        // organization.value,
        name.value,
        // version.value,
        // baseDirectory.value.relativeTo((ThisBuild / baseDirectory).value).get.getPath.split(File.separator),
        baseDirectory.value.getPath,
        thisProjectRef.value.project,
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
        AllDependencies(
          buildDependencies.value.classpath(thisProjectRef.value).map(classpathDep => {
            val depProject = classpathDep.project
            InterProjectDependency(depProject.project, classpathDep.configuration)
          }),
          libraryDependencies.value.flatMap(moduleID => {
            val dependency = LibraryDependency(
              moduleID.organization,
              moduleID.name,
              moduleID.crossVersion match {
                case Disabled => CrossVersion.Disabled
                case _: Binary => CrossVersion.Binary
                case _: Full => CrossVersion.Full
                case _: For3Use2_13 => CrossVersion.Constant("2.13")
                case _: For2_13Use3 => CrossVersion.Constant("3")
                case constant: Constant => CrossVersion.Constant(constant.value)
                case crossVersion =>
                  println(s"Dependency $moduleID with unsupported `CrossVersion`: $crossVersion")
                  CrossVersion.Disabled
              },
              moduleID.revision,
              moduleID.configurations,
              None,
              None,
              moduleID.exclusions.map(inclExclRule =>
                (inclExclRule.organization, inclExclRule.name)
              )
            )
            val explicitArtifacts = moduleID.explicitArtifacts
            if (explicitArtifacts.isEmpty)
              Seq(dependency)
            else
              explicitArtifacts.map(artifact =>
                dependency.copy(
                  tpe = Some(artifact.`type`) /*{
                    val tpe = artifact.`type`
                    Option.when(tpe != DefaultType)(tpe)
                  }*/,
                  classifier = artifact.classifier
                )
              )
          })
        )
      ),
    // `target.value` doesn't work in `globalSettings` and `buildSettings`, so this is added to `projectSettings`.
    millInitExportBuild := {
      val defaultBuildInfo = (ThisBuild / millInitBuildInfo).value
      val projects = millInitProject.all(ScopeFilter(inAnyProject)).value
      val buildExport = BuildExport(defaultBuildInfo, projects)

      val outputFile = target.value / "mill-init-build-export.json"
      IO.write(outputFile, write(buildExport))
      outputFile
    },
    millInitExportBuild / aggregate := false
  )
}
