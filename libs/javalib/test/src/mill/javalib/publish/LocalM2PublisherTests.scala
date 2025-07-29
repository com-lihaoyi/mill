package mill.javalib.publish

import mill.api.{PathRef, TaskCtx}
import mill.api.Logger
import mill.api.DummyLogger
import utest.{TestSuite, Tests, assert, test}

object LocalM2PublisherTests extends TestSuite {
  override def tests: Tests = Tests {

    implicit val dummyLog: TaskCtx.Log = new TaskCtx.Log {
      override def log: Logger = DummyLogger
    }

    def publishAndCheck(repo: os.Path): Unit = {
      val subrepo = repo / "group/org/id/version"

      os.write(repo / "jar", "JAR")
      os.write(repo / "doc", "DOC")
      os.write(repo / "src", "SRC")
      os.write(repo / "pom", "POM")
      os.write(repo / "extra", "EXTRA")

      val publisher = new LocalM2Publisher(repo)
      val artifact = Artifact("group.org", "id", "version")
      val res = publisher.publish(
        repo / "pom",
        artifact,
        Seq(
          PublishInfo.jar(PathRef(repo / "jar")),
          PublishInfo.sourcesJar(PathRef(repo / "src")),
          PublishInfo.docJar(PathRef(repo / "doc")),
          PublishInfo(
            file = PathRef(repo / "extra"),
            classifier = Some("extra"),
            ivyConfig = "compile"
          )
        )
      )
      val expected = Set(
        subrepo / "id-version.jar",
        subrepo / "id-version.pom",
        subrepo / "id-version-sources.jar",
        subrepo / "id-version-javadoc.jar",
        subrepo / "id-version-extra.jar"
      )
      assert(
        res.size == 5,
        res.toSet == expected,
        os.walk(subrepo).filter(os.isFile).toSet == expected,
        os.read(subrepo / "id-version.jar") == "JAR"
      )
    }

    test("Publish copies artifact files") - {
      val repo = os.temp.dir()
      publishAndCheck(repo)
    }

    test("Publish overwrites existing artifact files") - {
      val repo = os.temp.dir()

      // existing
      val subrepo = repo / "group/org/id/version"
      os.write(subrepo / "id-version.jar", "OLDJAR", createFolders = true)
      assert(os.read(subrepo / "id-version.jar") == "OLDJAR")

      // new publish
      publishAndCheck(repo)
    }

  }
}
