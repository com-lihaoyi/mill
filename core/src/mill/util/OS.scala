package mill.util

object OS {
  val isWindows: Boolean = System.getProperty("os.name").toLowerCase.startsWith("windows")
}
