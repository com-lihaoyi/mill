package mill
package scalalib

import java.io.{File, FileInputStream}
import java.lang.annotation.Annotation
import java.lang.reflect.Modifier
import java.util.zip.ZipInputStream
import javax.tools.ToolProvider

import ammonite.util.Util
import coursier.{Cache, Dependency, Fetch, Repository, Resolution}
import Dep.isDotty
import mill.Agg
import mill.eval.{PathRef, Result}
import mill.modules.Jvm
import mill.util.Ctx
import sbt.testing._

import scala.collection.mutable

object CompilationResult {
  implicit val jsonFormatter: upickle.default.ReadWriter[CompilationResult] = upickle.default.macroRW
}

// analysisFile is represented by os.Path, so we won't break caches after file changes
case class CompilationResult(analysisFile: os.Path, classes: PathRef)

object Lib{
  private val ReleaseVersion = raw"""(\d+)\.(\d+)\.(\d+)""".r
  private val MinorSnapshotVersion = raw"""(\d+)\.(\d+)\.([1-9]\d*)-SNAPSHOT""".r
  private val DottyVersion = raw"""0\.(\d+)\.(\d+).*""".r

  def scalaBinaryVersion(scalaVersion: String) = {
    scalaVersion match {
      case ReleaseVersion(major, minor, _) => s"$major.$minor"
      case MinorSnapshotVersion(major, minor, _) => s"$major.$minor"
      case DottyVersion(minor, _) => s"0.$minor"
      case _ => scalaVersion
    }
  }

  def grepJar(classPath: Agg[os.Path], name: String, version: String, sources: Boolean = false) = {
    val suffix = if (sources) "-sources" else ""
    val mavenStylePath = s"$name-$version$suffix.jar"
    val ivyStylePath = {
      val dir = if (sources) "srcs" else "jars"
      s"$version/$dir/$name$suffix.jar"
    }

    classPath
      .find(p => p.toString.endsWith(mavenStylePath) || p.toString.endsWith(ivyStylePath))
      .getOrElse(throw new Exception(s"Cannot find $mavenStylePath or $ivyStylePath"))
  }

  def depToDependencyJava(dep: Dep, platformSuffix: String = ""): Dependency = {
    assert(dep.cross.isConstant, s"Not a Java dependency: $dep")
    depToDependency(dep, "", platformSuffix)
  }

  def depToDependency(dep: Dep, scalaVersion: String, platformSuffix: String = ""): Dependency =
    dep.toDependency(
      binaryVersion = scalaBinaryVersion(scalaVersion),
      fullVersion = scalaVersion,
      platformSuffix = platformSuffix
    )

  def resolveDependenciesMetadata(repositories: Seq[Repository],
                                  depToDependency: Dep => coursier.Dependency,
                                  deps: TraversableOnce[Dep],
                                  mapDependencies: Option[Dependency => Dependency] = None) = {
    val depSeq = deps.toSeq
    mill.modules.Jvm.resolveDependenciesMetadata(
      repositories,
      depSeq.map(depToDependency),
      depSeq.filter(_.force).map(depToDependency),
      mapDependencies
    )
  }
  /**
    * Resolve dependencies using Coursier.
    *
    * We do not bother breaking this out into the separate ZincWorker classpath,
    * because Coursier is already bundled with mill/Ammonite to support the
    * `import $ivy` syntax.
    */
  def resolveDependencies(repositories: Seq[Repository],
                          depToDependency: Dep => coursier.Dependency,
                          deps: TraversableOnce[Dep],
                          sources: Boolean = false,
                          mapDependencies: Option[Dependency => Dependency] = None): Result[Agg[PathRef]] = {
    val depSeq = deps.toSeq
    mill.modules.Jvm.resolveDependencies(
      repositories,
      depSeq.map(depToDependency),
      depSeq.filter(_.force).map(depToDependency),
      sources,
      mapDependencies
    )
  }
  def scalaCompilerIvyDeps(scalaOrganization: String, scalaVersion: String) =
    if (isDotty(scalaVersion))
      Agg(ivy"$scalaOrganization::dotty-compiler:$scalaVersion".forceVersion())
    else
      Agg(
        ivy"$scalaOrganization:scala-compiler:$scalaVersion".forceVersion(),
        ivy"$scalaOrganization:scala-reflect:$scalaVersion".forceVersion()
      )

  def scalaRuntimeIvyDeps(scalaOrganization: String, scalaVersion: String) = Agg[Dep](
    ivy"$scalaOrganization:scala-library:$scalaVersion".forceVersion()
  )

  def listClassFiles(base: os.Path): Iterator[String] = {
    if (os.isDir(base)) os.walk(base).toIterator.filter(_.ext == "class").map(_.relativeTo(base).toString)
    else {
      val zip = new ZipInputStream(new FileInputStream(base.toIO))
      Iterator.continually(zip.getNextEntry).takeWhile(_ != null).map(_.getName).filter(_.endsWith(".class"))
    }
  }

  def discoverTests(cl: ClassLoader, framework: Framework, classpath: Agg[os.Path]) = {

    val fingerprints = framework.fingerprints()

    val testClasses = classpath.flatMap { base =>
      // Don't blow up if there are no classfiles representing
      // the tests to run Instead just don't run anything
      if (!os.exists(base)) Nil
      else listClassFiles(base).flatMap { path =>
        val cls = cl.loadClass(path.stripSuffix(".class").replace('/', '.'))
        val publicConstructorCount =
          cls.getConstructors.count(c => c.getParameterCount == 0 && Modifier.isPublic(c.getModifiers))

        if (Modifier.isAbstract(cls.getModifiers) || cls.isInterface || publicConstructorCount > 1) {
          None
        } else {
          (cls.getName.endsWith("$"), publicConstructorCount == 0) match{
            case (true, true) => matchFingerprints(cl, cls, fingerprints, isModule = true)
            case (false, false) => matchFingerprints(cl, cls, fingerprints, isModule = false)
            case _ => None
          }
        }
      }
    }

    testClasses
  }
  def matchFingerprints(cl: ClassLoader, cls: Class[_], fingerprints: Array[Fingerprint], isModule: Boolean) = {
    fingerprints.find {
      case f: SubclassFingerprint =>
        f.isModule == isModule &&
        cl.loadClass(f.superclassName()).isAssignableFrom(cls)

      case f: AnnotatedFingerprint =>
        val annotationCls = cl.loadClass(f.annotationName()).asInstanceOf[Class[Annotation]]
        f.isModule == isModule &&
        (
          cls.isAnnotationPresent(annotationCls) ||
          cls.getDeclaredMethods.exists(_.isAnnotationPresent(annotationCls))
        )

    }.map { f => (cls, f) }
  }

}
