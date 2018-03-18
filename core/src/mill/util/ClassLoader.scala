package mill.util

import java.net.{URL, URLClassLoader}

import ammonite.ops.Path
import io.github.retronym.java9rtexport.Export

object ClassLoader {
  private val home = new ThreadLocal[Path]

  def withHome[T](home: Path)(f: => T): T = {
    ClassLoader.home.set(home)
    try f finally ClassLoader.home.remove()
  }

  def create(urls: Seq[URL], parent: java.lang.ClassLoader): URLClassLoader = {
    val cl = new URLClassLoader(urls.toArray, parent)
    if (!ammonite.util.Util.java9OrAbove) return cl
    try {
      cl.loadClass("javax.script.ScriptEngineManager")
      cl
    } catch {
      case _: ClassNotFoundException =>
        val rtFile = {
          val path = home.get
          if (path == null) Export.export() else {
            val f = new java.io.File(path.toIO, s"rt-${System.getProperty("java.version")}.jar")
            if (!f.exists) {
              java.nio.file.Files.copy(Export.export().toPath, f.toPath)
            }
            f
          }
        }
        new URLClassLoader((urls ++ Some(rtFile.toURI.toURL)).toArray, parent)
    }
  }
}
