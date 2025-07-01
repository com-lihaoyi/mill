package mill.api.shared.internal

import mill.api.shared.Segments

trait ModuleApi {
  def moduleDirectChildren: Seq[ModuleApi]
  private[mill] def moduleDirJava: java.nio.file.Path
  def moduleSegments: Segments
}
