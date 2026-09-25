package mill.javalib.classgraph.impl

import java.io.File

import scala.jdk.CollectionConverters.*
import scala.util.Using

import io.github.classgraph.{ClassGraph, MethodInfo}
import mill.api.TaskCtx
import mill.javalib.classgraph.ClassgraphWorker

class ClassgraphWorkerImpl() extends ClassgraphWorker {

  /** Java 25+ allows public, protected, or package-access static {@code main} methods (JLS 12.1.4). */
  private def allowsNonPublicMainMethods: Boolean =
    Runtime.version().feature() >= 25

  private def isMainMethod(m: MethodInfo): Boolean = {
    if (m.getName() != "main" || !m.isStatic()) return false
    val ps = m.getParameterInfo()
    ps.length == 1 &&
    ps(0).getTypeSignatureOrTypeDescriptor().toString() == "java.lang.String[]" &&
    (m.isPublic() || (allowsNonPublicMainMethods && !m.isPrivate()))
  }

  def discoverMainClasses(classpath: Seq[os.Path])(using ctx: TaskCtx): Seq[String] = {

    val cp = classpath.map(_.toNIO.toString()).mkString(File.pathSeparator)
    ctx.log.debug(s"Scanning for mainclasses: ${cp}")

    val mainClasses = Using.resource(
      ClassGraph()
        .overrideClasspath(cp)
        .enableMethodInfo()
        .ignoreMethodVisibility()
        .scan()
    ) { scan =>
      scan
        .getAllClasses()
        .filter { classInfo =>
          val mainMethods = classInfo.getMethodInfo().filter(isMainMethod)
          !mainMethods.isEmpty()
        }
        .getNames()
    }

    ctx.log.debug(s"Found main classes: ${mainClasses}")
    mainClasses.asScala.toList
  }

}
