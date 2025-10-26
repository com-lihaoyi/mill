package mill.util

import java.io.InputStream
import java.net.URL
import java.util
import java.util.stream

/** A classloader that fails when it's invoked with helpful error messages. */
//noinspection ScalaDeprecation
class FailingClassLoader extends ClassLoader {
  override def loadClass(name: String): Class[_] =
    throw UnsupportedOperationException(s"loadClass(name=$name)")

  override def loadClass(name: String, resolve: Boolean): Class[_] =
    throw UnsupportedOperationException(s"loadClass(name=$name, resolve=$resolve)")

  override def getClassLoadingLock(className: String): AnyRef =
    throw UnsupportedOperationException(s"getClassLoadingLock(className=$className)")

  override def findClass(name: String): Class[_] =
    throw UnsupportedOperationException(s"findClass(name=$name")

  override def findClass(moduleName: String, name: String): Class[_] =
    throw UnsupportedOperationException(s"findClass(moduleName=$moduleName, name=$name)")

  override def findResource(moduleName: String, name: String): URL =
    throw UnsupportedOperationException(s"findResource(moduleName=$moduleName, name=$name)")

  override def getResource(name: String): URL =
    throw UnsupportedOperationException(s"getResource(name=$name)")

  override def getResources(name: String): util.Enumeration[URL] =
    throw UnsupportedOperationException(s"getResources(name=$name)")

  override def resources(name: String): stream.Stream[URL] =
    throw UnsupportedOperationException(s"resources(name=$name)")

  override def findResource(name: String): URL =
    throw UnsupportedOperationException(s"findResource(name=$name)")

  override def findResources(name: String): util.Enumeration[URL] =
    throw UnsupportedOperationException(s"findResources(name=$name)")

  override def getResourceAsStream(name: String): InputStream =
    throw UnsupportedOperationException(s"getResourceAsStream(name=$name)")

  override def definePackage(name: String, specTitle: String, specVersion: String, specVendor: String, implTitle: String, implVersion: String, implVendor: String, sealBase: URL): Package =
    throw UnsupportedOperationException(s"definePackage(name=$name, specTitle=$specTitle, specVersion=$specVersion, specVendor=$specVendor, implTitle=$implTitle, implVersion=$implVersion, implVendor=$implVendor, sealBase=$sealBase)")

  override def getPackage(name: String): Package =
    throw UnsupportedOperationException(s"getPackage(name=$name)")

  override def getPackages: Array[Package] =
    throw UnsupportedOperationException(s"getPackages")

  override def findLibrary(libname: String): String =
    throw UnsupportedOperationException(s"findLibrary(libname=$libname)")

  override def setDefaultAssertionStatus(enabled: Boolean): Unit =
    throw UnsupportedOperationException(s"setDefaultAssertionStatus(enabled=$enabled)")

  override def setPackageAssertionStatus(packageName: String, enabled: Boolean): Unit =
    throw UnsupportedOperationException(s"setPackageAssertionStatus(packageName=$packageName, enabled=$enabled)")

  override def setClassAssertionStatus(className: String, enabled: Boolean): Unit =
    throw UnsupportedOperationException(s"setClassAssertionStatus(className=$className, enabled=$enabled)")

  override def clearAssertionStatus(): Unit =
    throw UnsupportedOperationException(s"clearAssertionStatus()")
}
