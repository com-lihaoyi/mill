package mill.contrib.proguard

private[mill] object Export {
  def rtJarName = Export0.rtJarName
  def rt() = Export0.rt
  def rtTo(file: java.io.File, bool: Boolean): Boolean = Export0.rtTo(file, bool)
}
