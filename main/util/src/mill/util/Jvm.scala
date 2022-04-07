package mill.util

import mill.api.Loose.Agg
object Jvm {

  def inprocess[T](
      classPath: Agg[os.Path],
      classLoaderOverrideSbtTesting: Boolean,
      isolated: Boolean,
      closeContextClassLoaderWhenDone: Boolean,
      body: ClassLoader => T
  )(implicit ctx: mill.api.Ctx.Home): T = {
    val urls = classPath.map(_.toIO.toURI.toURL)
    val cl =
      if (classLoaderOverrideSbtTesting) {
        mill.api.ClassLoader.create(urls.iterator.toVector, null, sharedPrefixes = Seq("sbt.testing."))
      } else if (isolated) {
        mill.api.ClassLoader.create(urls.iterator.toVector, null)
      } else {
        mill.api.ClassLoader.create(urls.iterator.toVector, getClass.getClassLoader)
      }

    val oldCl = Thread.currentThread().getContextClassLoader
    Thread.currentThread().setContextClassLoader(cl)
    try {
      body(cl)
    } finally {
      if (closeContextClassLoaderWhenDone) {
        Thread.currentThread().setContextClassLoader(oldCl)
        cl.close()
      }
    }
  }
}
