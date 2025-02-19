package mill.util

import mill.api.*

import os.ProcessOutput

import java.io.*
import java.net.URLClassLoader
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.Files
import scala.util.Properties.isWin
import os.CommandResult

import java.util.jar.{JarEntry, JarOutputStream}
import scala.collection.mutable

object Jvm extends CoursierSupport {

  /**
   * Runs a JVM subprocess with the given configuration and returns a
   * [[os.CommandResult]] with it's aggregated output and error streams.
   *
   * @param mainClass The main class to run
   * @param mainArgs Args passed to the `mainClass` main method
   * @param javaHome Optional Java Home override
   * @param jvmArgs Arguments given to the forked JVM
   * @param classPath The classpath
   * @param cpPassingJarPath When `None`, the `-cp` parameter is used to pass the classpath
   *                        to the forked JVM.
   *                        When `Some`, a temporary empty JAR is created
   *                        which contains a `Class-Path` manifest entry containing the actual classpath.
   *                        This might help with long classpaths on OS'es (like Windows)
   *                        which only supports limited command-line length
   * @param env Environment variables used when starting the forked JVM
   * @param propagateEnv If `true` then the current process' environment variables are propagated to subprocess
   * @param cwd The working directory to be used by the forked JVM
   * @param stdin Standard input
   * @param stdout Standard output
   * @param stderr Standard error
   * @param mergeErrIntoOut If `true` then the error output is merged into standard output
   * @param timeout how long to wait in milliseconds for the subprocess to complete (-1 for no timeout)
   * @param shutdownGracePeriod if the timeout is enabled, how long in milliseconds for the subprocess
   *                            to gracefully terminate before attempting to forcibly kill it
   *                            (-1 for no kill, 0 for always kill immediately)
   * @param destroyOnExit Destroy on JVM exit
   * @param check if `true`, an exception will be thrown if process exits with a non-zero exit code
   */
  def callProcess(
      mainClass: String,
      mainArgs: Iterable[String] = Seq.empty,
      javaHome: Option[os.Path] = None,
      jvmArgs: Iterable[String] = Seq.empty,
      classPath: Iterable[os.Path],
      cpPassingJarPath: Option[os.Path] = None,
      env: Map[String, String] = Map.empty,
      propagateEnv: Boolean = true,
      cwd: os.Path = null,
      stdin: os.ProcessInput = os.Pipe,
      stdout: ProcessOutput = os.Pipe,
      stderr: ProcessOutput = os.Inherit,
      mergeErrIntoOut: Boolean = false,
      timeout: Long = -1,
      shutdownGracePeriod: Long = 100,
      destroyOnExit: Boolean = true,
      check: Boolean = true
  ): CommandResult = {
    val cp = cpPassingJarPath match {
      case Some(passingJarPath) if classPath.nonEmpty =>
        createClasspathPassingJar(passingJarPath, classPath.toSeq)
        Seq(passingJarPath)
      case _ => classPath
    }

    val commandArgs = (Vector(javaExe(javaHome)) ++
      jvmArgs ++
      Option.when(cp.nonEmpty)(Vector(
        "-cp",
        cp.mkString(java.io.File.pathSeparator)
      )).getOrElse(Vector.empty) ++
      Vector(mainClass) ++
      mainArgs).filterNot(_.isBlank)

    if (cwd != null) os.makeDir.all(cwd)

    val processResult = os.proc(commandArgs)
      .call(
        cwd = cwd,
        env = env,
        propagateEnv = propagateEnv,
        stdin = stdin,
        stdout = stdout,
        stderr = stderr,
        mergeErrIntoOut = mergeErrIntoOut,
        timeout = timeout,
        shutdownGracePeriod = shutdownGracePeriod,
        destroyOnExit = destroyOnExit,
        check = check
      )
    processResult
  }

  /**
   * Runs a JVM subprocess with the given configuration and streams
   * it's stdout and stderr to the console.
   *
   * @param mainClass The main class to run
   * @param mainArgs Args passed to the `mainClass` main method
   * @param javaHome Optional Java Home override
   * @param jvmArgs Arguments given to the forked JVM
   * @param classPath The classpath
   * @param cpPassingJarPath When `None`, the `-cp` parameter is used to pass the classpath
   *                        to the forked JVM.
   *                        When `Some`, a temporary empty JAR is created
   *                        which contains a `Class-Path` manifest entry containing the actual classpath.
   *                        This might help with long classpaths on OS'es (like Windows)
   *                        which only supports limited command-line length
   * @param env Environment variables used when starting the forked JVM
   * @param propagateEnv If `true` then the current process' environment variables are propagated to subprocess
   * @param cwd The working directory to be used by the forked JVM
   * @param stdin Standard input override
   * @param stdout Standard output override
   * @param stderr Standard error override
   * @param mergeErrIntoOut If `true` then the error output is merged into standard output
   * @param shutdownGracePeriod if the timeout is enabled, how long in milliseconds for the subprocess
   *                            to gracefully terminate before attempting to forcibly kill it
   *                            (-1 for no kill, 0 for always kill immediately)
   * @param destroyOnExit Destroy on JVM exit
   */
  def spawnProcess(
      mainClass: String,
      mainArgs: Iterable[String] = Seq.empty,
      javaHome: Option[os.Path] = None,
      jvmArgs: Iterable[String] = Seq.empty,
      classPath: Iterable[os.Path],
      cpPassingJarPath: Option[os.Path] = None,
      env: Map[String, String] = Map.empty,
      propagateEnv: Boolean = true,
      cwd: os.Path = null,
      stdin: os.ProcessInput = os.Pipe,
      stdout: ProcessOutput = os.Pipe,
      stderr: ProcessOutput = os.Inherit,
      mergeErrIntoOut: Boolean = false,
      shutdownGracePeriod: Long = 100,
      destroyOnExit: Boolean = true
  ): os.SubProcess = {
    val cp = cpPassingJarPath match {
      case Some(passingJarPath) if classPath.nonEmpty =>
        createClasspathPassingJar(passingJarPath, classPath.toSeq)
        Seq(passingJarPath)
      case _ => classPath
    }

    val commandArgs = (Vector(javaExe(javaHome)) ++
      jvmArgs ++
      Option.when(cp.nonEmpty)(
        Vector("-cp", cp.mkString(java.io.File.pathSeparator))
      ).getOrElse(Vector.empty) ++
      Vector(mainClass) ++
      mainArgs).filterNot(_.isBlank)

    if (cwd != null) os.makeDir.all(cwd)

    val process = os.proc(commandArgs).spawn(
      cwd = cwd,
      env = env,
      stdin = stdin,
      stdout = stdout,
      stderr = stderr,
      mergeErrIntoOut = mergeErrIntoOut,
      propagateEnv = propagateEnv,
      shutdownGracePeriod = shutdownGracePeriod,
      destroyOnExit = destroyOnExit
    )
    process
  }

  /**
   * Resolves a tool to a path under the currently used JDK (if known).
   */
  def jdkTool(toolName: String, javaHome: Option[os.Path]): String = {
    javaHome
      .map(_.toString())
      .orElse(sys.props.get("java.home"))
      .map(h =>
        if (isWin) new File(h, s"bin\\${toolName}.exe")
        else new File(h, s"bin/${toolName}")
      )
      .filter(f => f.exists())
      .fold(toolName)(_.getAbsolutePath())

  }

  def jdkTool(toolName: String): String = jdkTool(toolName, None)

  def javaExe(javaHome: Option[os.Path]): String = jdkTool("java", javaHome)

  def javaExe: String = javaExe(None)

  /**
   * Creates a `java.net.URLClassLoader` with specified parameters
   * @param classPath URLs from which to load classes and resources
   * @param parent parent class loader for delegation
   * @param sharedLoader loader used for shared classes
   * @param sharedPrefixes package prefix for classes that will be loaded by the `sharedLoader`
   * @return new classloader
   */
  def createClassLoader(
      classPath: Iterable[os.Path],
      parent: ClassLoader = null,
      sharedLoader: ClassLoader = getClass.getClassLoader,
      sharedPrefixes: Iterable[String] = Seq()
  ): URLClassLoader =
    new URLClassLoader(
      classPath.iterator.map(_.toNIO.toUri.toURL).toArray,
      refinePlatformParent(parent)
    ) {
      override def findClass(name: String): Class[?] =
        if (sharedPrefixes.exists(name.startsWith)) sharedLoader.loadClass(name)
        else super.findClass(name)
    }

  /**
   * @param classPath URLs from which to load classes and resources
   * @param parent parent class loader for delegation
   * @param sharedPrefixes package prefix for classes that will be loaded by the shared loader
   * @param f function that will be called with newly created classloader
   * @tparam T the return type of this function
   * @return return value of the function `f`
   */
  def withClassLoader[T](
      classPath: Iterable[os.Path],
      parent: ClassLoader = null,
      sharedPrefixes: Seq[String] = Seq.empty
  )(f: ClassLoader => T): T = {
    val oldClassloader = Thread.currentThread().getContextClassLoader
    val newClassloader =
      createClassLoader(classPath = classPath, parent = parent, sharedPrefixes = sharedPrefixes)
    Thread.currentThread().setContextClassLoader(newClassloader)
    try {
      f(newClassloader)
    } finally {
      Thread.currentThread().setContextClassLoader(oldClassloader)
      newClassloader.close()
    }
  }

  def createManifest(mainClass: Option[String]): JarManifest = {
    mainClass.foldLeft(JarManifest.MillDefault)((m, c) =>
      m.add((java.util.jar.Attributes.Name.MAIN_CLASS.toString, c))
    )
  }

  /**
   * Create a JAR file with default inflation level.
   *
   * @param jar The final JAR file
   * @param inputPaths The input paths resembling the content of the JAR file.
   *     Files will be directly included in the root of the archive,
   *     whereas for directories their content is added to the root of the archive.
   * @param manifest The JAR Manifest
   * @param fileFilter A filter to support exclusions of selected files
   * @param includeDirs If `true` the JAR archive will contain directory entries.
   *                    According to the ZIP specification, directory entries are not required.
   *                    In the Java ecosystem, most JARs have directory entries, so including them may reduce compatibility issues.
   *                    Directory entry names will result with a trailing `/`.
   * @param timestamp If specified, this timestamp is used as modification timestamp (mtime) for all entries in the JAR file.
   *                  Having a stable timestamp may result in reproducible files, if all other content, including the JAR Manifest, keep stable.
   */
  def createJar(
      jar: os.Path,
      inputPaths: Seq[os.Path],
      manifest: JarManifest = JarManifest.Empty,
      fileFilter: (os.Path, os.RelPath) => Boolean = (_, _) => true,
      includeDirs: Boolean = true,
      timestamp: Option[Long] = None
  ): os.Path = {

    val curTime = timestamp.getOrElse(System.currentTimeMillis())

    def mTime(file: os.Path) = timestamp.getOrElse(os.mtime(file))

    os.makeDir.all(jar / os.up)
    os.remove.all(jar)

    val seen = mutable.Set.empty[os.RelPath]
    val _ = seen.add(os.sub / "META-INF/MANIFEST.MF")

    val jarStream = new JarOutputStream(
      new BufferedOutputStream(Files.newOutputStream(jar.toNIO)),
      manifest.build
    )

    try {
      assert(inputPaths.iterator.forall(os.exists(_)))

      if (includeDirs) {
        val _ = seen.add(os.sub / "META-INF")
        val entry = new JarEntry("META-INF/")
        entry.setTime(curTime)
        jarStream.putNextEntry(entry)
        jarStream.closeEntry()
      }

      // Note: we only sort each input path, but not the whole archive
      for {
        p <- inputPaths
        (file, mapping) <-
          if (os.isFile(p)) Seq((p, os.sub / p.last))
          else os.walk(p).map(sub => (sub, sub.subRelativeTo(p))).sorted
        if (includeDirs || os.isFile(file)) && !seen(mapping) && fileFilter(p, mapping)
      } {
        val _ = seen.add(mapping)
        val name = mapping.toString() + (if (os.isDir(file)) "/" else "")
        val entry = new JarEntry(name)
        entry.setTime(mTime(file))
        jarStream.putNextEntry(entry)
        if (os.isFile(file)) jarStream.write(os.read.bytes(file))
        jarStream.closeEntry()
      }
      jar
    } finally {
      jarStream.close()
    }
  }

  def createClasspathPassingJar(jar: os.Path, classpath: Seq[os.Path]): Unit = {
    createJar(
      jar = jar,
      inputPaths = Seq(),
      manifest = JarManifest.MillDefault.add(
        "Class-Path" -> classpath.iterator.map(_.toNIO.toUri().toURL().toExternalForm()).mkString(
          " "
        )
      ),
      fileFilter = (_, _) => true
    )
  }

  def universalScript(
      shellCommands: String,
      cmdCommands: String,
      shebang: Boolean = false
  ): String = {
    Seq(
      if (shebang) "#!/usr/bin/env sh" else "",
      "@ 2>/dev/null # 2>nul & echo off & goto BOF\r",
      ":",
      shellCommands.replaceAll("\r\n|\n", "\n"),
      "exit",
      Seq(
        "",
        ":BOF",
        "setlocal",
        "@echo off",
        cmdCommands.replaceAll("\r\n|\n", "\r\n"),
        "endlocal",
        "exit /B %errorlevel%",
        ""
      ).mkString("\r\n")
    ).filterNot(_.isEmpty).mkString("\n")
  }

  def launcherUniversalScript(
      mainClass: String,
      shellClassPath: Seq[String],
      cmdClassPath: Seq[String],
      jvmArgs: Seq[String],
      shebang: Boolean = false,
      shellJvmArgs: Seq[String] = Nil,
      cmdJvmArgs: Seq[String] = Nil
  ): String = {

    universalScript(
      shellCommands = {
        val jvmArgsStr = (jvmArgs ++ shellJvmArgs).mkString(" ")
        val classpathStr = shellClassPath.mkString(":")

        s"""if [ -z "$$JAVA_HOME" ] ; then
           |  JAVACMD="java"
           |else
           |  JAVACMD="$$JAVA_HOME/bin/java"
           |fi
           |
           |exec "$$JAVACMD" $jvmArgsStr $$JAVA_OPTS -cp "$classpathStr" $mainClass "$$@"
           |""".stripMargin
      },
      cmdCommands = {
        val jvmArgsStr = (jvmArgs ++ cmdJvmArgs).mkString(" ")
        val classpathStr = cmdClassPath.mkString(";")
        s"""setlocal EnableDelayedExpansion
           |set "JAVACMD=java.exe"
           |if not "%JAVA_HOME%"=="" set "JAVACMD=%JAVA_HOME%\\bin\\java.exe"
           |
           |"%JAVACMD%" $jvmArgsStr %JAVA_OPTS% -cp "$classpathStr" $mainClass %*
           |
           |endlocal
           |""".stripMargin
      },
      shebang = shebang
    )
  }

  def createLauncher(mainClass: String, classPath: Seq[os.Path], jvmArgs: Seq[String])(implicit
      ctx: Ctx.Dest
  ): PathRef = {
    val isWin = scala.util.Properties.isWin
    val isBatch =
      isWin && !(org.jline.utils.OSUtils.IS_CYGWIN || org.jline.utils.OSUtils.IS_MSYSTEM)
    val outputPath = ctx.dest / (if (isBatch) "run.bat" else "run")
    val classPathStrs = classPath.map(_.toString)

    os.write(outputPath, launcherUniversalScript(mainClass, classPathStrs, classPathStrs, jvmArgs))

    if (!isWin) {
      val perms = Files.getPosixFilePermissions(outputPath.toNIO)
      perms.add(PosixFilePermission.GROUP_EXECUTE)
      perms.add(PosixFilePermission.OWNER_EXECUTE)
      perms.add(PosixFilePermission.OTHERS_EXECUTE)
      Files.setPosixFilePermissions(outputPath.toNIO, perms)
    }
    PathRef(outputPath)
  }

  /**
   * Return `ClassLoader.getPlatformClassLoader` for java 9 and above, if parent class loader is null,
   * otherwise return same parent class loader.
   * More details: https://docs.oracle.com/javase/9/migrate/toc.htm#JSMIG-GUID-A868D0B9-026F-4D46-B979-901834343F9E
   *
   * `ClassLoader.getPlatformClassLoader` call is implemented via runtime reflection, cause otherwise
   * mill could be compiled only with jdk 9 or above. We don't want to introduce this restriction now.
   */
  private def refinePlatformParent(parent: java.lang.ClassLoader): ClassLoader = {
    if (parent != null) parent
    else if (java9OrAbove) {
      // Make sure when `parent == null`, we only delegate java.* classes
      // to the parent getPlatformClassLoader. This is necessary because
      // in Java 9+, somehow the getPlatformClassLoader ends up with all
      // sorts of other non-java stuff on it's classpath, which is not what
      // we want for an "isolated" classloader!
      classOf[ClassLoader]
        .getMethod("getPlatformClassLoader")
        .invoke(null)
        .asInstanceOf[ClassLoader]
    } else {
      // With Java 8 we want a clean classloader that still contains classes
      // coming from com.sun.* etc.
      // We get the application classloader parent which happens to be of
      // type sun.misc.Launcher$ExtClassLoader
      // We can't call the method directly since it would not compile on Java 9+
      // So we load it via reflection to allow compilation in Java 9+ but only
      // on Java 8
      val launcherClass = getClass.getClassLoader().loadClass("sun.misc.Launcher")
      val getLauncherMethod = launcherClass.getMethod("getLauncher")
      val launcher = getLauncherMethod.invoke(null)
      val getClassLoaderMethod = launcher.getClass().getMethod("getClassLoader")
      val appClassLoader = getClassLoaderMethod.invoke(launcher).asInstanceOf[ClassLoader]
      appClassLoader.getParent()
    }
  }

  private val java9OrAbove: Boolean =
    !System.getProperty("java.specification.version").startsWith("1.")

}
