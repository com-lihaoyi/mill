package millbuild

import build_.package_ as build
import coursier.MavenRepository
import mill.PathRef
import mill.T
import mill.Task
import mill.scalalib.Dep
import mill.scalalib.JavaModule
import mill.scalalib.PublishModule
import mill.scalalib.publish.Developer
import mill.scalalib.publish.License
import mill.scalalib.publish.PomSettings
import mill.scalalib.publish.VersionControl

trait MillPublishJavaModule extends MillJavaModule with PublishModule {

  def artifactName = "mill-" + super.artifactName()
  def publishVersion = build.millVersion()
  def publishProperties = super.publishProperties() ++ Map(
    "info.releaseNotesURL" -> Settings.changelogUrl
  )
  def pomSettings = MillPublishJavaModule.commonPomSettings(artifactName())
  def javacOptions = Seq("-source", "11", "-target", "11", "-encoding", "UTF-8")
}

object MillPublishJavaModule {
  def commonPomSettings(artifactName: String) = {
    PomSettings(
      description = artifactName,
      organization = Settings.pomOrg,
      url = Settings.projectUrl,
      licenses = Seq(License.MIT),
      versionControl = VersionControl.github(Settings.githubOrg, Settings.githubRepo),
      developers = Seq(
        Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi"),
        Developer("lefou", "Tobias Roeser", "https://github.com/lefou")
      )
    )
  }
}
