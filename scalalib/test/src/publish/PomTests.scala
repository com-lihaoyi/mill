package mill.scalalib.publish

import utest._
import mill._

import scala.xml.{NodeSeq, XML}

object PomTests extends TestSuite {

  def tests: Tests = Tests {
    val artifactId = "mill-scalalib_2.12"
    val artifact =
      Artifact("com.lihaoyi", "mill-scalalib_2.12", "0.0.1")
    val deps = Agg(
      Dependency(Artifact("com.lihaoyi", "mill-main_2.12", "0.1.4"), Scope.Compile),
      Dependency(Artifact("org.scala-sbt", "test-interface", "1.0"), Scope.Compile),
      Dependency(
        Artifact("com.lihaoyi", "pprint_2.12", "0.5.3"),
        Scope.Compile,
        exclusions = List("com.lihaoyi" -> "fansi_2.12", "*" -> "sourcecode_2.12")
      )
    )
    val settings = PomSettings(
      description = "mill-scalalib",
      organization = "com.lihaoyi",
      url = "https://github.com/lihaoyi/mill",
      licenses = Seq(License.`MIT`),
      versionControl = VersionControl.github("lihaoyi", "mill"),
      developers = List(
        Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi", None, None),
        Developer(
          "rockjam",
          "Nikolai Tatarinov",
          "https://github.com/rockjam",
          Some("80pct done Inc."),
          Some("https://80pctdone.com/")
        )
      )
    )

    'fullPom - {
      val fullPom = pomXml(artifact, deps, artifactId, settings)

      'topLevel - {
        assert(
          singleText(fullPom \ "modelVersion") == "4.0.0",
          singleText(fullPom \ "name") == artifactId,
          singleText(fullPom \ "groupId") == artifact.group,
          singleText(fullPom \ "artifactId") == artifact.id,
          singleText(fullPom \ "packaging") == "jar",
          singleText(fullPom \ "description") == settings.description,
          singleText(fullPom \ "version") == artifact.version,
          singleText(fullPom \ "url") == settings.url
        )
      }

      'licenses - {
        val licenses = fullPom \ "licenses" \ "license"

        assert(licenses.size == 1)

        val license = licenses.head
        val pomLicense = settings.licenses.head
        assert(
          singleText(license \ "name") == pomLicense.name,
          singleText(license \ "url") == pomLicense.url,
          singleText(license \ "distribution") == pomLicense.distribution
        )
      }

      'scm - {
        val scm = (fullPom \ "scm").head
        val pomScm = settings.versionControl

        assert(
          optText(scm \ "connection") == pomScm.connection,
          optText(scm \ "developerConnection") == pomScm.developerConnection,
          optText(scm \ "tag").isEmpty,
          optText(scm \ "url") == pomScm.browsableRepository
        )
      }

      'developers - {
        val developers = fullPom \ "developers" \ "developer"

        assert(developers.size == 2)

        val pomDevelopers = settings.developers

        assert(
          singleText(developers.head \ "id") == pomDevelopers.head.id,
          singleText(developers.head \ "name") == pomDevelopers.head.name,
          optText(developers.head \ "organization").isEmpty,
          optText(developers.head \ "organizationUrl").isEmpty
        )

        assert(
          singleText(developers.last \ "id") == pomDevelopers.last.id,
          singleText(developers.last \ "name") == pomDevelopers.last.name,
          optText(developers.last \ "organization") == pomDevelopers.last.organization,
          optText(developers.last \ "organizationUrl") == pomDevelopers.last.organizationUrl
        )
      }

      'dependencies - {
        val dependencies = fullPom \ "dependencies" \ "dependency"

        assert(dependencies.size == 3)

        val pomDeps = deps.indexed

        dependencies.zipWithIndex.foreach {
          case (dep, index) =>
            assert(
              singleText(dep \ "groupId") == pomDeps(index).artifact.group,
              singleText(dep \ "artifactId") == pomDeps(index).artifact.id,
              singleText(dep \ "version") == pomDeps(index).artifact.version,
              optText(dep \ "scope").isEmpty,
              (dep \ "exclusions").zipWithIndex.forall { case (node, j) =>
                singleText(node \ "exclusion" \ "groupId") == pomDeps(index).exclusions(j)._1 &&
                  singleText(node \ "exclusion" \ "artifactId") == pomDeps(index).exclusions(j)._2
              }
            )
        }
      }
    }

    'pomEmptyScm - {
      val updatedSettings = settings.copy(
        versionControl = VersionControl(
          browsableRepository = Some("git://github.com/lihaoyi/mill.git"),
          connection = None,
          developerConnection = None,
          tag = None
        )
      )
      val pomEmptyScm = pomXml(artifact, deps, artifactId, updatedSettings)

      'scm - {
        val scm = (pomEmptyScm \ "scm").head
        val pomScm = updatedSettings.versionControl

        assert(
          optText(scm \ "connection").isEmpty,
          optText(scm \ "developerConnection").isEmpty,
          optText(scm \ "tag").isEmpty,
          optText(scm \ "url") == pomScm.browsableRepository
        )
      }
    }

    'pomNoLicenses - {
      val updatedSettings = settings.copy(licenses = Seq.empty)
      val pomNoLicenses = pomXml(artifact, deps, artifactId, updatedSettings)

      'licenses - {
        assert(
          (pomNoLicenses \ "licenses").nonEmpty,
          (pomNoLicenses \ "licenses" \ "licenses").isEmpty
        )
      }
    }

    'pomNoDeps - {
      val pomNoDeps =
        pomXml(artifact, dependencies = Agg.empty, artifactId = artifactId, pomSettings = settings)

      'dependencies - {
        assert(
          (pomNoDeps \ "dependencies").nonEmpty,
          (pomNoDeps \ "dependencies" \ "dependency").isEmpty
        )
      }
    }

    'pomNoDevelopers - {
      val updatedSettings = settings.copy(developers = Seq.empty)
      val pomNoDevelopers = pomXml(artifact, deps, artifactId, updatedSettings)

      'developers - {
        assert(
          (pomNoDevelopers \ "developers").nonEmpty,
          (pomNoDevelopers \ "developers" \ "developer").isEmpty
        )
      }
    }
  }

  def pomXml(
      artifact: Artifact,
      dependencies: Agg[Dependency],
      artifactId: String,
      pomSettings: PomSettings
  ) =
    XML.loadString(Pom(artifact, dependencies, artifactId, pomSettings))

  def singleText(seq: NodeSeq) =
    seq
      .map(_.text)
      .headOption
      .getOrElse(throw new RuntimeException("seq was empty"))

  def optText(seq: NodeSeq) = seq.map(_.text).headOption

}
