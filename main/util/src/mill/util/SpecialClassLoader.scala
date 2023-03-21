package mill.util

import java.io.ByteArrayInputStream
import java.net.{URL, URLClassLoader, URLConnection, URLStreamHandler}
import java.nio.ByteBuffer
import java.util.Collections




import scala.collection.mutable



/**
 * Classloader used to implement the jar-downloading
 * command-evaluating logic in Ammonite.
 *
 * http://stackoverflow.com/questions/3544614/how-is-the-control-flow-to-findclass-of
 */
class SpecialClassLoader(parent: ClassLoader,
                         parentSignature: Seq[(Either[String, java.net.URL], Long)],
                         var specialLocalClasses: Set[String],
                         urls: URL*)
  extends java.net.URLClassLoader(urls.toArray, parent){

  /**
   * Files which have been compiled, stored so that our special
   * classloader can get at them.
   */
  val newFileDict = mutable.Map.empty[String, Array[Byte]]
  def addClassFile(name: String, bytes: Array[Byte]) = {
    val tuple = Left(name) -> bytes.sum.hashCode().toLong
    classpathSignature0 = classpathSignature0 ++ Seq(tuple)
    newFileDict(name) = bytes
  }
  def findClassPublic(name: String) = findClass(name)
  override def findClass(name: String): Class[_] = {
    val loadedClass = this.findLoadedClass(name)
    if (loadedClass != null) loadedClass
    else if (newFileDict.contains(name)) {
      val bytes = newFileDict(name)
      defineClass(name, bytes, 0, bytes.length)
    }else if (specialLocalClasses(name)) {


      val parts = name.split('.')
      val resource = os.resource / parts.dropRight(1) / (parts.last + ".class")

      val bytes =
        try Some(os.read.bytes(resource))
        catch{case e: os.ResourceNotFoundException => None}

      bytes match{
        case Some(b) => defineClass(name, b, 0, b.length)
        case None => super.findClass(name)
      }

    } else super.findClass(name)
  }
  def add(url: URL) = {
    classpathSignature0 = classpathSignature0 ++ Seq(jarSignature(url))
    this.addURL(url)
  }

  override def close() = {
    // DO NOTHING LOLZ

    // Works around
    // https://github.com/scala/scala/commit/6181525f60588228ce99ab3ef2593ecfcfd35066
    // Which for some reason started mysteriously closing these classloaders in 2.12
  }

  private def jarSignature(url: URL) = {
    val lastModified = SpecialClassLoader.urlLastModified(url).getOrElse(0L)
    Right(url) -> lastModified
  }

  private var classpathSignature0 = parentSignature
  def classpathSignature: Seq[(Either[String, java.net.URL], Long)] = classpathSignature0
  def classpathHash(wd: Option[os.Path]) = {
    Util.md5Hash(
      // Include the current working directory in the classpath hash, to make
      // sure different scripts cached
      wd.map(_.toString.getBytes).iterator ++
        classpathSignature0.iterator.map { case (path, long) =>
          val buffer = ByteBuffer.allocate(8)
          buffer.putLong(long)
          path.toString.getBytes ++ buffer.array()
        }
    )

  }
  def allJars: Seq[URL] = {
    this.getURLs ++ ( parent match{
      case t: SpecialClassLoader => t.allJars
      case _ => Nil
    })
  }

  override def findResource(name: String) = {
    getURLFromFileDict(name).getOrElse(super.findResource(name))
  }

  override def findResources(name: String) = getURLFromFileDict(name) match {
    case Some(u) => Collections.enumeration(Collections.singleton(u))
    case None    => super.findResources(name)
  }

  private def getURLFromFileDict(name: String) = {
    val className = name.stripSuffix(".class").replace('/', '.')
    newFileDict.get(className) map { x =>
      new URL(null, s"memory:${name}", new URLStreamHandler {
        override def openConnection(url: URL): URLConnection = new URLConnection(url) {
          override def connect() = ()
          override def getInputStream = new ByteArrayInputStream(x)
        }
      })
    }
  }

  def cloneClassLoader(parent: ClassLoader = null): SpecialClassLoader = {

    // FIXME Not tailrec

    val newParent =
      if (parent == null)
        getParent match {
          case s: SpecialClassLoader => s.cloneClassLoader()
          case p => p
        }
      else
        parent

    val clone = new SpecialClassLoader(newParent,
      parentSignature,
      specialLocalClasses,
      getURLs.toSeq: _*)
    clone.newFileDict ++= newFileDict
    clone.classpathSignature0 = classpathSignature0

    clone
  }

  def inMemoryClasses: Map[String, Array[Byte]] =
    newFileDict.toMap

}


object SpecialClassLoader{
  val simpleNameRegex = "[a-zA-Z0-9_]+".r

  /**
   * Stats all jars on the classpath, and loose class-files in the current
   * classpath that could conceivably be part of some package, and aggregates
   * their names and mtimes as a "signature" of the current classpath
   *
   * When looking for loose class files, we skip folders whose names are not
   * valid java identifiers. Otherwise, the "current classpath" often contains
   * the current directory, which in an SBT or Maven project contains hundreds
   * or thousands of files which are not on the classpath. Empirically, this
   * heuristic improves perf by greatly cutting down on the amount of files we
   * need to mtime in many common cases.
   */
  def initialClasspathSignature(
                                 classloader: ClassLoader
                               ): Seq[(Either[String, java.net.URL], Long)] = {
    val allClassloaders = {
      val all = mutable.Buffer.empty[ClassLoader]
      var current = classloader
      while(current != null && current != ClassLoader.getSystemClassLoader){
        all.append(current)
        current = current.getParent
      }
      all
    }

    def findMtimes(d: java.nio.file.Path): Seq[(Either[String, java.net.URL], Long)] = {
      def skipSuspicious(path: os.Path) = {
        // Leave out sketchy files which don't look like package names or
        // class files
        (simpleNameRegex.findPrefixOf(path.last) != Some(path.last)) &&
          !path.last.endsWith(".class")
      }
      os.walk(os.Path(d), skip = skipSuspicious).map(x => (Right(x), os.mtime(x))).map {
        case (e, lm) =>
          (e.right.map(_.toNIO.toUri.toURL), lm)
      }
    }


    val classpathRoots =
      allClassloaders
        .collect{case cl: java.net.URLClassLoader => cl.getURLs}
        .flatten

    val bootClasspathRoots = sys.props("java.class.path")
      .split(java.io.File.pathSeparator)
      .map(java.nio.file.Paths.get(_).toAbsolutePath.toUri.toURL)

    val mtimes = (bootClasspathRoots ++ classpathRoots).flatMap{ p =>
      if (p.getProtocol == "file") {
        val f = java.nio.file.Paths.get(p.toURI)
        if (!java.nio.file.Files.exists(f)) Nil
        else if (java.nio.file.Files.isDirectory(f)) findMtimes(f)
        else Seq(Right(p) -> os.mtime(os.Path(f)))
      } else
        SpecialClassLoader.urlLastModified(p).toSeq.map((Right(p), _))
    }

    mtimes
  }

  def urlLastModified(url: URL): Option[Long] = {
    if (url.getProtocol == "file") {
      val path = os.Path(java.nio.file.Paths.get(url.toURI()).toFile(), os.root)
      if (os.exists(path)) Some(os.mtime(path)) else None
    } else {
      var c: java.net.URLConnection = null
      try {
        c = url.openConnection()
        Some(c.getLastModified)
      } catch {
        case e: java.io.FileNotFoundException =>
          None
      } finally {
        if (c != null)
          scala.util.Try(c.getInputStream.close())
      }
    }
  }
}
