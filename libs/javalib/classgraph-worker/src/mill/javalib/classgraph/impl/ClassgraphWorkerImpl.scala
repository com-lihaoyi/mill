package mill.javalib.classgraph.impl

import java.io.File

import scala.jdk.CollectionConverters._
import scala.util.Using

import io.github.classgraph.ClassGraph
import mill.api.TaskCtx
import mill.javalib.classgraph.ClassgraphWorker

class ClassgraphWorkerImpl() extends ClassgraphWorker {

  def discoverMainClasses(classpath: Seq[os.Path])(using ctx: TaskCtx): Seq[String] = {

    val cp = classpath.map(_.toNIO.toString()).mkString(File.pathSeparator)
    ctx.log.debug(s"Scanning for mainclasses: ${cp}")

    val mainClasses = Using.resource(
      new ClassGraph()
        .overrideClasspath(cp)
        .enableMethodInfo()
        .scan()
    ) { scan =>
      scan
        .getAllClasses()
        .filter { classInfo =>
          val mainMethods = classInfo.getMethodInfo().filter { m =>
            m.getName() == "main" && m.isPublic() && m.isStatic() && {
              val ps = m.getParameterInfo()
              ps.length == 1 &&
              ps(0).getTypeSignatureOrTypeDescriptor().toString() == "java.lang.String[]"
            }
          }
          !mainMethods.isEmpty()
        }
        .getNames()
    }

    ctx.log.debug(s"Found main classes: ${mainClasses}")
    mainClasses.asScala.toList
  }

}
