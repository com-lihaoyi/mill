package mill.mima.internal

private[mima] object Utils {
  def scalaBinaryVersion(version: String): String = {
    mill.scalalib.api.ZincWorkerUtil.scalaBinaryVersion(version)
  }
}
