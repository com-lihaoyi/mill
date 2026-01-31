package mill.javalib

import mill.Task
import mill.api.Discover
import mill.javalib.publish.{Developer, License, PomSettings, VersionControl}
import mill.testkit.TestRootModule
import mill.util.TokenReaders.*

object PublishSonatypeCentralTestModule extends TestRootModule {
  object testProject extends JavaModule with SonatypeCentralPublishModule {
    def publishOrganization = Task.Input {
      Task.env.get("MILL_TESTS_PUBLISH_ORG").getOrElse("io.github.lihaoyi")
    }

    def publishVersion = Task.Input {
      if (Task.env.contains("MILL_TESTS_PUBLISH_ORG")) "0.0.1-SNAPSHOT"
      else "0.0.1"
    }

    def pomSettings = Task {
      PomSettings(
        description = "Hello",
        organization = publishOrganization(),
        url = "https://github.com/lihaoyi/example",
        licenses = Seq(License.MIT),
        versionControl = VersionControl.github("lihaoyi", "example"),
        developers = Seq(Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi"))
      )
    }
  }

  lazy val millDiscover = Discover[this.type]
}
