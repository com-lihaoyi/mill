package mill.util

import java.net.{URL, URLClassLoader}

import io.github.retronym.java9rtexport.Export

object ClassLoader {
  def create(urls: Seq[URL],
             parent: java.lang.ClassLoader)
            (implicit ctx: Ctx.Home): URLClassLoader = {
    val cl = new URLClassLoader(urls.toArray, parent)
    if (!ammonite.util.Util.java9OrAbove) return cl
    try {
      cl.loadClass("javax.script.ScriptEngineManager")
      cl
    } catch {
      case _: ClassNotFoundException =>
        val path = ctx.home
        val rtFile = new java.io.File(path.toIO, s"rt-${System.getProperty("java.version")}.jar")
        if (!rtFile.exists) {
          java.nio.file.Files.copy(Export.export().toPath, rtFile.toPath)
        }
        new URLClassLoader((urls ++ Some(rtFile.toURI.toURL)).toArray, parent)
    }
  }
}
