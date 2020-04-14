package mill.api

import java.net.{URL, URLClassLoader}

import io.github.retronym.java9rtexport.Export

object ClassLoader {
  def java9OrAbove = !System.getProperty("java.specification.version").startsWith("1.")
  def create(urls: Seq[URL],
             parent: java.lang.ClassLoader,
             sharedPrefixes: Seq[String] = Seq())
            (implicit ctx: Ctx.Home): URLClassLoader = {
    new URLClassLoader(
      makeUrls(urls).toArray,
      refinePlatformParent(parent)
    ) {
      val allSharedPrefixes = sharedPrefixes :+ "com.sun.jna"
      override def findClass(name: String): Class[_] = {
        if (allSharedPrefixes.exists(name.startsWith)) getClass.getClassLoader.loadClass(name)
        else super.findClass(name)
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
    if (!java9OrAbove || parent != null) parent
    else {
      // Make sure when `parent == null`, we only delegate java.* classes
      // to the parent getPlatformClassLoader. This is necessary because
      // in Java 9+, somehow the getPlatformClassLoader ends up with all
      // sorts of other non-java stuff on it's classpath, which is not what
      // we want for an "isolated" classloader!
      classOf[ClassLoader]
        .getMethod("getPlatformClassLoader")
        .invoke(null)
        .asInstanceOf[ClassLoader]
    }
  }

  private def makeUrls(urls: Seq[URL])(implicit ctx: Ctx.Home): Seq[URL] = {
    if (java9OrAbove) {
      urls :+ Export.rtAt(ctx.home.toIO).toURI.toURL
    } else {
      urls
    }
  }
}
