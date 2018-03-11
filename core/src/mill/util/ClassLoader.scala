package mill.util

import java.net.URL

import io.github.retronym.java9rtexport.Export

import scala.reflect.internal.util.ScalaClassLoader.URLClassLoader

object ClassLoader {
  def create(urls: Seq[URL], parent: java.lang.ClassLoader): URLClassLoader = {
    val rtOpt = if (ammonite.util.Util.java9OrAbove) Some(Export.export().toURI.toURL) else None
    new URLClassLoader(urls ++ rtOpt, parent)
  }
}
