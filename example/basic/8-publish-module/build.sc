// == Publish Module
import mill._, scalalib._, publish._

object foo extends ScalaModule with PublishModule {
  def scalaVersion = "2.13.8"
  def publishVersion = "0.0.1"

  def pomSettings = PomSettings(
    description = "Hello",
    organization = "com.lihaoyi",
    url = "https://github.com/lihaoyi/example",
    licenses = Seq(License.MIT),
    versionControl = VersionControl.github("lihaoyi", "example"),
    developers = Seq(
      Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi")
    )
  )
}

// This is an example `ScalaModule` with added publishing capabilities via
// `PublishModule`. This requires that you define an additional
// `publishVersion` and `pomSettings` with the relevant metadata, and provides
// the `.publishLocal` and `publishSigned` tasks for publishing locally to the
// machine or to the central maven repository

/** Example Usage

> ./mill foo.publishLocal
Publishing Artifact(com.lihaoyi,foo_2.13,0.0.1) to ivy repo

*/

// The `artifactName` defaults to the name of your module (in this case `foo`)
// but can be overridden. The `organization` is defined in `pomSettings`.
//
// You may also check and update the values of `sonatypeUri` and `sonatypeSnapshotUri`,
// which may not be correct if you have a newer Sonatype account (when created after Feb. 2021).
//
// === Staging Releases
//
// Once you've mixed in `PublishModule`, you can publish your libraries to maven
// central via:
//
// [source,bash]
// ----
// mill mill.scalalib.PublishModule/publishAll \
//         foo.publishArtifacts \
//         lihaoyi:$SONATYPE_PASSWORD \
//         --gpgArgs --passphrase=$GPG_PASSWORD,--batch,--yes,-a,-b
// ----
//
// This uploads them to `oss.sonatype.org` where you can log-in and stage/release
// them manually. You can also pass in the `--release true` flag to perform the
// staging/release automatically:
//
// [NOTE]
// --
// Sonatype credentials can be passed via environment variables (`SONATYPE_USERNAME`
// and `SONATYPE_PASSWORD`) or by passing second or `--sonatypeCreds` argument in format
// `username:password`. Consider using environment variables over the direct CLI passing
// due to security risks.
// --
//
// [source,bash]
// ----
// mill mill.scalalib.PublishModule/publishAll \
//         foo.publishArtifacts \
//         lihaoyi:$SONATYPE_PASSWORD \
//         --gpgArgs --passphrase=$GPG_PASSWORD,--batch,--yes,-a,-b \
//         --release true
// ----
//
// If you want to publish/release multiple modules, you can use the `_` or `__`
// wildcard syntax:
//
// [source,bash]
// ----
// mill mill.scalalib.PublishModule/publishAll \
//         __.publishArtifacts \
//         lihaoyi:$SONATYPE_PASSWORD \
//         --gpgArgs --passphrase=$GPG_PASSWORD,--batch,--yes,-a,-b \
//         --release true
// ----
//
// To publish to repository other than `oss.sonaytype.org` such as internal hosted
// nexus at `example.company.com`, you can pass in the `--sonatypeUri` and
// `--sonatypeSnapshotUri` parameters to uploads to different site:
//
// [source,bash]
// ----
// mill mill.scalalib.PublishModule/publishAll \
//         foo.publishArtifacts \
//         lihaoyi:$SONATYPE_PASSWORD \
//         --sonatypeUri http://example.company.com/release \
//         --sonatypeSnaphostUri http://example.company.com/snapshot
// ----
//
// [NOTE]
// --
// Since Feb. 2021 any new Sonatype accounts have been created on
// `s01.oss.sonatype.org`, so you'll want to ensure you set the relevant URIs to match.
//
// * `https://s01.oss.sonatype.org/service/local` - for the `--sonatypeUri`
// * `https://s01.oss.sonatype.org/content/repositories/snapshots` - for `sonatypeSnapshotUri`
// --
//
// === Non-Staging Releases (classic Maven uploads)
//
// If the site does not support staging releases as `oss.sonatype.org` and `s01.oss.sonatype.org` do (for
// example, a self-hosted OSS nexus site), you can pass in the
// `--stagingRelease false` option to simply upload release artifacts to corresponding
// maven path under `sonatypeUri` instead of staging path.
//
// [source,bash]
// ----
// mill mill.scalalib.PublishModule/publishAll \
//         foo.publishArtifacts \
//         lihaoyi:$SONATYPE_PASSWORD \
//         --sonatypeUri http://example.company.com/release \
//         --stagingRelease false
// ----
//