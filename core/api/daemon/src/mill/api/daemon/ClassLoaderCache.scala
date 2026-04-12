package mill.api.daemon

import mill.api.daemon.internal.PathRefApi

import java.net.URLClassLoader

trait ClassLoaderCache {
  def get(classPath: Seq[PathRefApi]): URLClassLoader
  def release(classPath: Seq[PathRefApi]): Unit
}
