package mill.util

import java.net.{URL, URLClassLoader}

import ammonite.ops._
import io.github.retronym.java9rtexport.Export

object ClassLoader {

  def create(urls: Seq[URL], parent: java.lang.ClassLoader)(
      implicit ctx: Ctx.Home): URLClassLoader = {
    new URLClassLoader(
      makeUrls(urls).toArray,
      refinePlatformParent(parent)
    )
  }

  def create(urls: Seq[URL],
             parent: java.lang.ClassLoader,
             customFindClass: String => Option[Class[_]])(
      implicit ctx: Ctx.Home): URLClassLoader = {
    new URLClassLoader(
      makeUrls(urls).toArray,
      refinePlatformParent(parent)
    ) {
      override def findClass(name: String): Class[_] = {
        customFindClass(name).getOrElse(super.findClass(name))
      }
    }
  }

  private def refinePlatformParent(parent: java.lang.ClassLoader): ClassLoader = {
    if (ammonite.util.Util.java9OrAbove) {
      if (parent == null)
        classOf[ClassLoader]
          .getMethod("getPlatformClassLoader")
          .invoke(null)
          .asInstanceOf[ClassLoader]
      else parent
    } else {
      parent
    }
  }

  private def makeUrls(urls: Seq[URL])(implicit ctx: Ctx.Home): Seq[URL] = {
    if (ammonite.util.Util.java9OrAbove) {
      val rtFile = ctx.home / s"rt-${System.getProperty("java.version")}.jar"
      if (!exists(rtFile)) {
        cp(Path(Export.export()), rtFile)
      }
      urls :+ rtFile.toNIO.toUri.toURL
    } else {
      urls
    }
  }
}
