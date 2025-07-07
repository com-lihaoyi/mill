package mill.api.daemon.internal

import mill.api.daemon.Segments

trait ModuleApi {
  def moduleDirectChildren: Seq[ModuleApi]
  private[mill] def moduleDirJava: java.nio.file.Path
  def moduleSegments: Segments
}
