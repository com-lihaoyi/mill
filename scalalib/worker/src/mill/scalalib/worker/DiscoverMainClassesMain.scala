package mill.scalalib.worker

import java.io.{File, PrintWriter}
import mill.api.{internal, DummyOutputStream}
import scala.annotation.tailrec
import scala.tools.nsc.{CloseableRegistry, Settings}
import scala.tools.nsc.classpath.{AggregateClassPath, ClassPathFactory}
import scala.tools.scalap.{ByteArrayReader, Classfile, JavaWriter}
import scala.util.Using

@internal object DiscoverMainClassesMain {
  // copied from ModuleUtils
  private def recursive[T <: String](start: T, deps: T => Seq[T]): Seq[T] = {

    @tailrec def rec(
        seenModules: List[T],
        toAnalyze: List[(List[T], List[T])]
    ): List[T] = {
      toAnalyze match {
        case Nil => seenModules
        case traces :: rest =>
          traces match {
            case (_, Nil) => rec(seenModules, rest)
            case (trace, cand :: remaining) =>
              if (trace.contains(cand)) {
                // cycle!
                val rendered =
                  (cand :: (cand :: trace.takeWhile(_ != cand)).reverse).mkString(" -> ")
                val msg = s"cycle detected: ${rendered}"
                println(msg)
                throw sys.error(msg)
              }
              rec(
                if (seenModules.contains(cand)) seenModules
                else { seenModules ++ Seq(cand) },
                toAnalyze = ((cand :: trace, deps(cand).toList)) :: (trace, remaining) :: rest
              )
          }
      }
    }

    rec(
      seenModules = List(),
      toAnalyze = List((List(start), deps(start).toList))
    ).reverse
  }

  def main(args: Array[String]): Unit =
    os.Path.pathSerializer.withValue(new mill.internal.MillPathSerializer(Seq(os.pwd -> os.sub))) {
      val classpath = args(0).split(",").map(os.Path(_)).toSeq
      apply(classpath).foreach(println)
    }

  def apply(classpath: Seq[os.Path]): Seq[String] = {
    val cp = classpath.map(_.toNIO.toString()).mkString(File.pathSeparator)

    val settings = new Settings()
    Using.resource(new CloseableRegistry) { registry =>
      val path = AggregateClassPath(
        new ClassPathFactory(settings, registry).classesInExpandedPath(cp)
      )

      val mainClasses = for {
        foundPackage <- recursive("", (p: String) => path.packages(p).map(_.name))
        classFile <- path.classes(foundPackage)
        path = os.Path(classFile.file.file)
        if path.ext == "class"
        cf = {
          val bytes = os.read.bytes(path)
          val reader = new ByteArrayReader(bytes)
          new Classfile(reader)
        }
        jw = new JavaWriter(cf, new PrintWriter(DummyOutputStream))
        method <- cf.methods
        static = jw.isStatic(method.flags)
        methodName = jw.getName(method.name)
        methodType = jw.getType(method.tpe)
        if static && methodName == "main" && methodType == "(scala.Array[java.lang.String]): scala.Unit"
        className = jw.getClassName(cf.classname)
      } yield className

      mainClasses
    }
  }
}
