package mill.contrib.sonatypecentral

import mill.*
import scalalib.*
import define.{ExternalModule, ModuleRef}

trait SonatypeCentralPublishModule extends mill.scalalib.SonatypeCentralPublishModule

object SonatypeCentralPublishModule extends ExternalModule {

  private lazy val other = ModuleRef(mill.scalalib.SonatypeCentralPublishModule)

  val defaultCredentials: String = other().defaultCredentials
  val defaultReadTimeout: Int = other().defaultReadTimeout
  val defaultConnectTimeout: Int = other().defaultConnectTimeout
  val defaultAwaitTimeout: Int = other().defaultConnectTimeout
  val defaultShouldRelease: Boolean = other().defaultShouldRelease

  def publishAll(
      publishArtifacts: mill.main.Tasks[PublishModule.PublishData],
      username: String = defaultCredentials,
      password: String = defaultCredentials,
      shouldRelease: Boolean = defaultShouldRelease,
      gpgArgs: String = "",
      readTimeout: Int = defaultReadTimeout,
      connectTimeout: Int = defaultConnectTimeout,
      awaitTimeout: Int = defaultAwaitTimeout,
      bundleName: String = ""
  ): Command[Unit] = other().publishAll(
    publishArtifacts = publishArtifacts,
    username = username,
    password = password,
    shouldRelease = shouldRelease,
    gpgArgs = gpgArgs,
    readTimeout = readTimeout,
    connectTimeout = connectTimeout,
    awaitTimeout = awaitTimeout,
    bundleName = bundleName
  )

  lazy val millDiscover: mill.define.Discover = mill.define.Discover[this.type]
}
