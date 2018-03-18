package mill.util

import java.net.{URL, URLClassLoader}

import io.github.retronym.java9rtexport.Export

object ClassLoader {
  def create(urls: Seq[URL], parent: java.lang.ClassLoader): URLClassLoader = {
    val cl = new URLClassLoader(urls.toArray, parent)
    if (!ammonite.util.Util.java9OrAbove) return cl
    try {
      cl.loadClass("javax.script.ScriptEngineManager")
      cl
    } catch {
      case _: ClassNotFoundException =>
        new URLClassLoader((urls ++ Some(Export.export().toURI.toURL)).toArray, parent)
    }
  }
}
