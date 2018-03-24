package mill.util

import java.net.{URL, URLClassLoader}

import ammonite.ops._
import io.github.retronym.java9rtexport.Export

object ClassLoader {
  def create(urls: Seq[URL], parent: java.lang.ClassLoader)(
      implicit ctx: Ctx.Home): URLClassLoader = {
    if (ammonite.util.Util.java9OrAbove) {
      val platformParent =
        if (parent == null)
          classOf[ClassLoader]
            .getMethod("getPlatformClassLoader")
            .invoke(null)
            .asInstanceOf[ClassLoader]
        else parent

      val rtFile = ctx.home / s"rt-${System.getProperty("java.version")}.jar"
      if (!exists(rtFile)) {
        cp(Path(Export.export()), rtFile)
      }
      new URLClassLoader((urls :+ rtFile.toNIO.toUri.toURL).toArray,platformParent)
    } else {
      new URLClassLoader(urls.toArray, parent)
    }
  }
}
