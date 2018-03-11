package mill.util

import java.net.{URL, URLClassLoader}

import io.github.retronym.java9rtexport.Export

object ClassLoader {
  def create(urls: Seq[URL], parent: java.lang.ClassLoader): URLClassLoader = {
    val rtOpt = if (ammonite.util.Util.java9OrAbove) Some(Export.export().toURI.toURL) else None
    new URLClassLoader((urls ++ rtOpt).toArray, parent)
  }
}
