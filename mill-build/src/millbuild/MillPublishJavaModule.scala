package millbuild

import mill.*
import mill.scalalib.*

import coursier.MavenRepository
import mill.scalalib.{Dep, JavaModule}
import mill.{Agg, PathRef, T, Task}

import mill.scalalib.PublishModule
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}

import build_.{package_ => build}

trait MillPublishJavaModule extends MillJavaModule with PublishModule {
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

  def artifactName = "mill-" + super.artifactName()
  def publishVersion = build.millVersion()
  def publishProperties = super.publishProperties() ++ Map(
    "info.releaseNotesURL" -> Settings.changelogUrl
  )
  def pomSettings = commonPomSettings(artifactName())
  def javacOptions = Seq("-source", "1.8", "-target", "1.8", "-encoding", "UTF-8")
}
