package scala.scalanative.build

object MillUtils {
  def targetsWindows(config: Config): Boolean = config.targetsWindows
  def targetsMac(config: Config): Boolean = config.targetsMac
}
