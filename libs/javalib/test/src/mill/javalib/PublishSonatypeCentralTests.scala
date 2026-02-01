package mill.javalib

import mill.api.{Discover, Task}
import mill.javalib.publish.{Developer, License, PomSettings, VersionControl}
import mill.testkit.TestRootModule
import mill.testkit.internal.SonatypeCentralTestUtils
import mill.util.Tasks
import utest.*
import mill.util.TokenReaders.*
object PublishSonatypeCentralTestModule extends TestRootModule {
  val TestPgpSecretBase64 =
    "LS0tLS1CRUdJTiBQR1AgUFJJVkFURSBLRVkgQkxPQ0stLS0tLQoKeFZnRWFHekhpeFlKS3dZQkJBSGFSdzhCQVFkQWxQamhsaGo5MUtZUnhDQXFtaUZNMjR1UEVDL0kxemR0CnlWS2dRR1lENHZZQUFQOW9jK0ZFQzQ2dkt6b0tNWVE3M1Jvemh4UDE3WWhUZnZwRFBwYk1CZHNZQ2c2RQp6VEpwYnk1bmFYUm9kV0l1WVhKMGRYSmhlaTUwWlhOMFVISnZhbVZqZENCaWIzUWdQR0Z6UUdGeWRIVnkKWVhvdWJtVjBQc0tNQkJBV0NnQWRCUUpvYk1lTEJBc0pCd2dERlFnS0JCWUFBZ0VDR1FFQ0d3TUNIZ0VBCklRa1FBMkRDK3lxemF1RVdJUVRnUmJWQ05LcVpxRTFkdDB3RFlNTDdLck5xNFR1L0FQNHRDYzZpYWNUdQpZVEJBa2Q3UDZOM1E1VTZjbGdnSElVQ2lRL3lIbmFvVHZ3RUExbU92M2MydEVORGtrdnF5Ujl2YVhWNHEKZlBEckNDRmRTUTR0anpMY3hnVEhYUVJvYk1lTEVnb3JCZ0VFQVpkVkFRVUJBUWRBUHpzMjV5RERLSC80Cm1KNmtMU1dLSExITXJEWUZMWGVHOTNWRTluSVY0Q0FEQVFnSEFBRC9aQ1hVMDhqMkZTU2VYQWdZaFZzNwp2akVDQjQweTA2TjdaM0pqaitCSko3Z08xc0o0QkJnV0NBQUpCUUpvYk1lTEFoc01BQ0VKRUFOZ3d2c3EKczJyaEZpRUU0RVcxUWpTcW1haE5YYmRNQTJEQyt5cXphdUgrY2dEL1QxRUVkVDl1WnR6L255bGk1OHR0CjYxaWNLcndyU3kzSTBBRDNYWWErcm40QS9qWEZlZXNsNVBZZWtpU0ZzNVZGNUczRVNpWmY0amJxZXlOWQpLd09ENVIwSwo9WDhSdQotLS0tLUVORCBQR1AgUFJJVkFURSBLRVkgQkxPQ0stLS0tLQo="

  object normal extends JavaModule with SonatypeCentralPublishModule {
    def publishVersion = "0.0.1"

    def pomSettings = Task {
      PomSettings(
        description = "Hello",
        organization = "io.github.lihaoyi",
        url = "https://github.com/lihaoyi/example",
        licenses = Seq(License.MIT),
        versionControl = VersionControl.github("lihaoyi", "example"),
        developers = Seq(Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi"))
      )
    }
  }

  object snapshot extends JavaModule with SonatypeCentralPublishModule {
    def publishVersion = "0.0.1-SNAPSHOT"

    override def sonatypeCentralShouldRelease = Task { false }

    def pomSettings = Task {
      PomSettings(
        description = "Hello",
        organization = "io.github.lihaoyi",
        url = "https://github.com/lihaoyi/example",
        licenses = Seq(License.MIT),
        versionControl = VersionControl.github("lihaoyi", "example"),
        developers = Seq(Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi"))
      )
    }
  }

  lazy val millDiscover = Discover[this.type]
}

object PublishSonatypeCentralTests extends TestSuite {
  private val PublishTask = PublishSonatypeCentralTestModule.normal.publishSonatypeCentral()
  private val PublishDirName = os.SubPath("normal/publishSonatypeCentral.dest")
  private val PublishAllTask = SonatypeCentralPublishModule.publishAll(
    publishArtifacts = Tasks(Seq(PublishSonatypeCentralTestModule.normal.publishArtifacts))
  )
  private val PublishAllDirName =
    os.SubPath("mill.javalib.SonatypeCentralPublishModule/publishAll.dest")
  private val ResourcePath =
    os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "publish-sonatype-central"


  private def dryRun(task: Task[Unit], dirName: os.SubPath): Unit = {
    SonatypeCentralTestUtils.dryRunWithKey(
      task,
      dirName,
      PublishSonatypeCentralTestModule.TestPgpSecretBase64,
      None,
      PublishSonatypeCentralTestModule,
      ResourcePath,
      group = "io.github.lihaoyi",
      artifactId = "normal",
      version = "0.0.1"
    )
  }

  val tests: Tests = Tests {
    test("module") - dryRun(PublishTask, PublishDirName)
    test("externalModule") - dryRun(PublishAllTask, PublishAllDirName)
  }
}


object PublishSonatypeCentralSnapshotTests extends TestSuite {
  import SonatypeCentralTestUtils.*

  private val PublishTask = PublishSonatypeCentralTestModule.snapshot.publishSonatypeCentral()
  private val PublishDirName = os.SubPath("snapshot/publishSonatypeCentral.dest")
  private val PublishAllTask = SonatypeCentralPublishModule.publishAll(
    publishArtifacts = Tasks(Seq(PublishSonatypeCentralTestModule.snapshot.publishArtifacts))
  )
  private val PublishAllDirName =
    os.SubPath("mill.javalib.SonatypeCentralPublishModule/publishAll.dest")
  private val ResourcePath =
    os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "publish-sonatype-central"

  def dryRun(task: Task[Unit], dirName: os.SubPath): Unit =
    dryRunWithKey(
      task,
      dirName,
      Some(PublishSonatypeCentralTestModule.TestPgpSecretBase64),
      None,
      PublishSonatypeCentralTestModule,
      ResourcePath
    ) { repoDir =>
      val publishedDir = repoDir / "io" / "github" / "lihaoyi" / "snapshot"

      val rootMetadataFile = publishedDir / "maven-metadata.xml"
      assert(os.exists(rootMetadataFile))

      val rootMetadataContents = os.read(rootMetadataFile)
      assert(rootMetadataContents.contains("<version>0.0.1-SNAPSHOT</version>"))

      val publishedVersionDir = publishedDir / "0.0.1-SNAPSHOT"

      val metadataFile = publishedVersionDir / "maven-metadata.xml"
      assert(os.exists(metadataFile))

      val metadataContents: String = os.read(metadataFile)
      assert(metadataContents.contains("<version>0.0.1-SNAPSHOT</version>"))

      val timestampRegex = """<timestamp>(\d{8}\.\d{6})</timestamp>""".r
      val timestamp = timestampRegex.findFirstMatchIn(metadataContents).map(_.group(1)).getOrElse {
        throw new Exception(
          s"No timestamp found via $timestampRegex in $metadataFile:\n$metadataContents"
        )
      }

      val expectedFiles = Vector(
        rootMetadataFile,
        publishedDir / "maven-metadata.xml.md5",
        publishedDir / "maven-metadata.xml.sha1",
        metadataFile,
        publishedVersionDir / "maven-metadata.xml.md5",
        publishedVersionDir / "maven-metadata.xml.sha1",
        publishedVersionDir / s"snapshot-0.0.1-$timestamp-1.jar",
        publishedVersionDir / s"snapshot-0.0.1-$timestamp-1.jar.md5",
        publishedVersionDir / s"snapshot-0.0.1-$timestamp-1.jar.sha1",
        publishedVersionDir / s"snapshot-0.0.1-$timestamp-1-sources.jar",
        publishedVersionDir / s"snapshot-0.0.1-$timestamp-1-sources.jar.md5",
        publishedVersionDir / s"snapshot-0.0.1-$timestamp-1-sources.jar.sha1",
        publishedVersionDir / s"snapshot-0.0.1-$timestamp-1-javadoc.jar",
        publishedVersionDir / s"snapshot-0.0.1-$timestamp-1-javadoc.jar.md5",
        publishedVersionDir / s"snapshot-0.0.1-$timestamp-1-javadoc.jar.sha1",
        publishedVersionDir / s"snapshot-0.0.1-$timestamp-1.pom",
        publishedVersionDir / s"snapshot-0.0.1-$timestamp-1.pom.md5",
        publishedVersionDir / s"snapshot-0.0.1-$timestamp-1.pom.sha1"
      )
      val actualFiles = os.walk(publishedDir).toVector
      val missingFiles = expectedFiles.filterNot(actualFiles.contains)
      assert(missingFiles.isEmpty)

      val checksumTargets = Vector(
        publishedDir / "maven-metadata.xml",
        publishedVersionDir / "maven-metadata.xml",
        publishedVersionDir / s"snapshot-0.0.1-$timestamp-1.jar",
        publishedVersionDir / s"snapshot-0.0.1-$timestamp-1-sources.jar",
        publishedVersionDir / s"snapshot-0.0.1-$timestamp-1-javadoc.jar",
        publishedVersionDir / s"snapshot-0.0.1-$timestamp-1.pom"
      )
      checksumTargets.foreach { file =>
        assertChecksumMatches(file, os.Path(file.toString + ".md5"), "MD5")
        assertChecksumMatches(file, os.Path(file.toString + ".sha1"), "SHA1")
      }
    }

  val tests: Tests = Tests {

    test("module") - dryRun(PublishTask, PublishDirName)
    test("externalModule") - dryRun(PublishAllTask, PublishAllDirName)
  }
}
