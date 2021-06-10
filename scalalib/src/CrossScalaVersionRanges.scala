package mill.scalalib

import mill.api.PathRef
import mill.scalalib.api.Util
import mill.T

trait CrossScalaVersionRanges extends CrossModuleBase {
  val crossScalaVersions = millOuterCtx.crossInstances.map(
    _.asInstanceOf[CrossScalaModule].crossScalaVersion
  )
  override def scalaVersionDirectoryNames =
    super.scalaVersionDirectoryNames ++
      Util.versionSpecificSources(crossScalaVersion, crossScalaVersions)
}
