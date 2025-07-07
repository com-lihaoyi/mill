package mill.api.daemonapi.internal

import mill.api.daemonapi.Segments

trait ModuleApi {
  def moduleDirectChildren: Seq[ModuleApi]
  private[mill] def moduleDirJava: java.nio.file.Path
  def moduleSegments: Segments
}
