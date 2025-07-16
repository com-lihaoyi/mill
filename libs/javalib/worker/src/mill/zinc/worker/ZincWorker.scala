package mill.zinc.worker

import mill.api.PathRef
import mill.javalib.api.{JvmWorkerApi, JvmWorkerUtil, Versions}
import mill.javalib.worker.{CompilersApi, ZincCompilerBridge}
import mill.util.{CachedFactory, RefCountedClassLoaderCache}
import mill.zinc.worker.ZincWorker.{JavaCompilerCacheKey, ScalaCompilerCacheKey, ScalaCompilerCached, getLocalOrCreateJavaTools, libraryJarNameGrep}
import sbt.internal.inc.classpath.ClasspathUtil
import sbt.internal.inc.{ScalaInstance, ZincUtil, javac}
import xsbti.compile.{ClasspathOptions, Compilers, IncrementalCompiler, JavaTools}

import java.io.File
import java.net.URLClassLoader
import scala.collection.mutable
import scala.util.Properties.isWin


// TODO review: apply javaHome, javacRuntimeOptions
class ZincWorker(
  compilerBridge: ZincCompilerBridge,
  jobs: Int
) extends CompilersApi {
  // compilers.scalac().scalaInstance().version().startsWith("3.")

  private val incrementalCompiler = new sbt.internal.inc.IncrementalCompilerImpl()
  private val compilerBridgeLocks: mutable.Map[String, Object] = mutable.Map.empty[String, Object]

  private val classloaderCache = new RefCountedClassLoaderCache(
    sharedLoader = getClass.getClassLoader,
    sharedPrefixes = Seq("xsbti")
  ) {
    override def extraRelease(cl: ClassLoader): Unit = {
      for {
        cls <- {
          try Some(cl.loadClass("scala.tools.nsc.classpath.FileBasedCache$"))
          catch {
            case _: ClassNotFoundException => None
          }
        }
        moduleField <- {
          try Some(cls.getField("MODULE$"))
          catch {
            case _: NoSuchFieldException => None
          }
        }
        module = moduleField.get(null)
        timerField <- {
          try Some(cls.getDeclaredField("scala$tools$nsc$classpath$FileBasedCache$$timer"))
          catch {
            case _: NoSuchFieldException => None
          }
        }
        _ = timerField.setAccessible(true)
        timerOpt0 = timerField.get(module)
        getOrElseMethod <- timerOpt0.getClass.getMethods.find(_.getName == "getOrElse")
        timer <-
          Option(getOrElseMethod.invoke(timerOpt0, null).asInstanceOf[java.util.Timer])
      } {

        timer.cancel()
      }

    }
  }

  object scalaCompilerCache extends CachedFactory[ScalaCompilerCacheKey, ScalaCompilerCached] {

    override def maxCacheSize: Int = jobs

    override def setup(key: ScalaCompilerCacheKey): ScalaCompilerCached = {
      import key._

      val combinedCompilerJars = combinedCompilerClasspath.iterator.map(_.path.toIO).toArray

      val compiledCompilerBridge = compileBridgeIfNeeded(
        scalaVersion,
        scalaOrganization,
        compilerClasspath
      )
      val classLoader = classloaderCache.get(key.combinedCompilerClasspath)
      val scalaInstance = new ScalaInstance(
        version = key.scalaVersion,
        loader = classLoader,
        loaderCompilerOnly = classLoader,
        loaderLibraryOnly = ClasspathUtil.rootLoader,
        libraryJars = Array(libraryJarNameGrep(
          compilerClasspath,
          // we don't support too outdated dotty versions
          // and because there will be no scala 2.14, so hardcode "2.13." here is acceptable
          if (JvmWorkerUtil.isDottyOrScala3(key.scalaVersion)) "2.13." else key.scalaVersion
        ).path.toIO),
        compilerJars = combinedCompilerJars,
        allJars = combinedCompilerJars,
        explicitActual = None
      )
      val compilers = incrementalCompiler.compilers(
        javaTools = getLocalOrCreateJavaTools(),
        scalac = ZincUtil.scalaCompiler(scalaInstance, compiledCompilerBridge.toIO)
      )
      ScalaCompilerCached(classLoader, compilers)
    }

    override def teardown(key: ScalaCompilerCacheKey, value: ScalaCompilerCached): Unit = {
      classloaderCache.release(key.combinedCompilerClasspath)
    }
  }

  object javaOnlyCompilerCache extends CachedFactory[JavaCompilerCacheKey, Compilers] {

    override def setup(key: JavaCompilerCacheKey): Compilers = {
      // Only options relevant for the compiler runtime influence the cached instance
      // Keep the classpath as written by the user
      val classpathOptions = ClasspathOptions.of(
        /*bootLibrary*/ false,
        /*compiler*/ false,
        /*extra*/ false,
        /*autoBoot*/ false,
        /*filterLibrary*/ false
      )

      val dummyFile = new java.io.File("")
      // Zinc does not have an entry point for Java-only compilation, so we need
      // to make up a dummy ScalaCompiler instance.
      val scalac = ZincUtil.scalaCompiler(
        new ScalaInstance(
          version = "",
          loader = null,
          loaderCompilerOnly = null,
          loaderLibraryOnly = null,
          libraryJars = Array(dummyFile),
          compilerJars = Array(dummyFile),
          allJars = new Array(0),
          explicitActual = Some("")
        ),
        dummyFile,
        classpathOptions // this is used for javac too
      )

      val javaTools = getLocalOrCreateJavaTools()
      val compilers = incrementalCompiler.compilers(javaTools, scalac)
      compilers
    }

    override def teardown(key: JavaCompilerCacheKey, value: Compilers): Unit = ()

    override def maxCacheSize: Int = jobs
  }



  /**
   * If needed, compile (for Scala 2) or download (for Dotty) the compiler bridge.
   *
   * @return a path to the directory containing the compiled classes, or to the downloaded jar file
   */
  def compileBridgeIfNeeded(
    scalaVersion: String,
    scalaOrganization: String,
    compilerClasspath: Seq[PathRef]
  ): os.Path = {
    compilerBridge match {
      case ZincCompilerBridge.Compiled(forScalaVersion) => forScalaVersion(scalaVersion).path
      case ZincCompilerBridge.Provider(ctx0, bridgeProvider) =>
        val workingDir = ctx0.dest / s"zinc-${Versions.zinc}" / scalaVersion
        val lock = synchronized(compilerBridgeLocks.getOrElseUpdate(scalaVersion, new Object()))
        val compiledDest = workingDir / "compiled"
        lock.synchronized {
          if (os.exists(compiledDest / "DONE")) compiledDest
          else {
            val provided = bridgeProvider(scalaVersion, scalaOrganization)
            provided.classpath match {
              case None => provided.bridgeJar.path
              case Some(bridgeClasspath) =>
                ZincCompilerBridge.compile(
                  ctx0,
                  workingDir,
                  compiledDest,
                  scalaVersion,
                  compilerClasspath,
                  bridgeClasspath,
                  provided.bridgeJar.path
                )
                os.write(compiledDest / "DONE", "")
                compiledDest
            }
          }
        }
    }
  }
}
object ZincWorker {
  /** Java compiler options, without the `-J` options (the Java runtime options). */
  case class JavaCompilerOptions private (options: Seq[String]) {
    {
      val runtimeOptions = options.filter(_.startsWith("-J"))
      if (runtimeOptions.nonEmpty) throw new IllegalArgumentException(
        s"Providing Java runtime options to javac is not supported."
      )
    }
  }
  object JavaCompilerOptions {
    def apply(options: Seq[String]): (runtime: JavaRuntimeOptions, compiler: JavaCompilerOptions) = {
      val prefix = "-J"
      val (runtimeOptions0, compilerOptions) = options.partition(_.startsWith(prefix))
      val runtimeOptions = JavaRuntimeOptions(runtimeOptions0.map(_.drop(prefix.length)))
      (runtimeOptions, new JavaCompilerOptions(compilerOptions))
    }
  }

  /** Options that are passed to the Java runtime. */
  case class JavaRuntimeOptions(options: Seq[String])

  case class ScalaCompilerCacheKey(
    scalaVersion: String,
    compilerClasspath: Seq[PathRef],
    scalacPluginClasspath: Seq[PathRef],
    scalaOrganization: String,
    javacOptions: JavaCompilerOptions
  ) {
    val combinedCompilerClasspath: Seq[PathRef] = compilerClasspath ++ scalacPluginClasspath

    val compilersSig: Int =
      scalaVersion.hashCode + combinedCompilerClasspath.hashCode + scalaOrganization.hashCode +
        javacOptions.hashCode
        // TODO review: `+ scalacPluginClasspath.hashCode`?
  }

  case class ScalaCompilerCached(classLoader: URLClassLoader, compilers: Compilers)

  case class JavaCompilerCacheKey(javacOptions: JavaCompilerOptions)


  private def getLocalOrCreateJavaTools(): JavaTools = {
    val compiler = javac.JavaCompiler.local.getOrElse(javac.JavaCompiler.fork())
    val docs = javac.Javadoc.local.getOrElse(javac.Javadoc.fork())
    javac.JavaTools(compiler, docs)
  }

  private def libraryJarNameGrep(compilerClasspath: Seq[PathRef], scalaVersion: String): PathRef =
    JvmWorkerUtil.grepJar(compilerClasspath, "scala-library", scalaVersion, sources = false)


}
