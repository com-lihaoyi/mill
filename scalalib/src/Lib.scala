package mill
package scalalib

import java.io.{File, FileInputStream}
import java.lang.annotation.Annotation
import java.lang.reflect.Modifier
import java.util.zip.ZipInputStream
import javax.tools.ToolProvider

import ammonite.util.Util
import coursier.{Dependency, Fetch, Repository, Resolution}
import mill.scalalib.api.Util.isDotty
import mill.Agg
import mill.eval.{PathRef, Result}
import mill.modules.Jvm
import mill.api.Ctx
import sbt.testing._

import scala.collection.mutable


object Lib{
  def depToDependencyJava(dep: Dep, platformSuffix: String = ""): Dependency = {
    assert(dep.cross.isConstant, s"Not a Java dependency: $dep")
    depToDependency(dep, "", platformSuffix)
  }

  def depToDependency(dep: Dep, scalaVersion: String, platformSuffix: String = ""): Dependency =
    dep.toDependency(
      binaryVersion = mill.scalalib.api.Util.scalaBinaryVersion(scalaVersion),
      fullVersion = scalaVersion,
      platformSuffix = platformSuffix
    )

  def resolveDependenciesMetadata(repositories: Seq[Repository],
                                  depToDependency: Dep => coursier.Dependency,
                                  deps: TraversableOnce[Dep],
                                  mapDependencies: Option[Dependency => Dependency] = None,
                                  ctx: Option[mill.util.Ctx.Log] = None) = {
    val depSeq = deps.toSeq
    mill.modules.Jvm.resolveDependenciesMetadata(
      repositories,
      depSeq.map(depToDependency),
      depSeq.filter(_.force).map(depToDependency),
      mapDependencies,
      ctx
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
                          mapDependencies: Option[Dependency => Dependency] = None,
                          ctx: Option[mill.util.Ctx.Log] = None): Result[Agg[PathRef]] = {
    val depSeq = deps.toSeq
    mill.modules.Jvm.resolveDependencies(
      repositories,
      depSeq.map(depToDependency),
      depSeq.filter(_.force).map(depToDependency),
      sources,
      mapDependencies,
      ctx
    )
  }
  def scalaCompilerIvyDeps(scalaOrganization: String, scalaVersion: String) =
    if (mill.scalalib.api.Util.isDotty(scalaVersion))
      Agg(
        ivy"$scalaOrganization::dotty-compiler:$scalaVersion".forceVersion()
      )
    else
      Agg(
        ivy"$scalaOrganization:scala-compiler:$scalaVersion".forceVersion(),
        ivy"$scalaOrganization:scala-reflect:$scalaVersion".forceVersion()
      )

  def scalaDocIvyDeps(scalaOrganization: String, scalaVersion: String) =
    if (mill.scalalib.api.Util.isDotty(scalaVersion))
      Agg(
        ivy"$scalaOrganization::dotty-doc:$scalaVersion".forceVersion()
      )
    else
      // in Scala <= 2.13, the scaladoc tool is included in the compiler
      scalaCompilerIvyDeps(scalaOrganization, scalaVersion)

  def scalaRuntimeIvyDeps(scalaOrganization: String, scalaVersion: String) =
    if (mill.scalalib.api.Util.isDotty(scalaVersion))
      Agg(
        // note that dotty-library has a binary version suffix, hence the :: is necessary here
        ivy"$scalaOrganization::dotty-library:$scalaVersion".forceVersion()
      )
    else
      Agg(
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
            cls.getDeclaredMethods.exists(_.isAnnotationPresent(annotationCls)) ||
            cls.getMethods.exists(m => m.isAnnotationPresent(annotationCls) && Modifier.isPublic(m.getModifiers()))
          )

    }.map { f => (cls, f) }
  }

}
