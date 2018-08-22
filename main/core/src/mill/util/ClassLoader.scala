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
    ) {
      override def findClass(name: String): Class[_] = {
        if (name.startsWith("com.sun.jna")) getClass.getClassLoader.loadClass(name)
        else super.findClass(name)
      }
    }
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
        if (name.startsWith("com.sun.jna")) getClass.getClassLoader.loadClass(name)
        else customFindClass(name).getOrElse(super.findClass(name))
      }
    }
  }

  /**
    *  Return `ClassLoader.getPlatformClassLoader` for java 9 and above, if parent class loader is null,
    *  otherwise return same parent class loader.
    *  More details: https://docs.oracle.com/javase/9/migrate/toc.htm#JSMIG-GUID-A868D0B9-026F-4D46-B979-901834343F9E
    *
    *  `ClassLoader.getPlatformClassLoader` call is implemented via runtime reflection, cause otherwise
    *  mill could be compiled only with jdk 9 or above. We don't want to introduce this restriction now.
    */
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
      urls :+ Export.rtAt(ctx.home.toIO).toURI.toURL
    } else {
      urls
    }
  }
}
