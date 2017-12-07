package mill.scalaplugin

import java.io.FileInputStream
import java.lang.annotation.Annotation
import java.net.URLClassLoader
import java.util.zip.ZipInputStream

import ammonite.ops.{Path, ls, pwd}
import mill.util.Ctx.LogCtx
import mill.util.PrintLogger
import sbt.testing._

import scala.collection.mutable

object TestRunner {
  def listClassFiles(base: Path): Iterator[String] = {
    if (base.isDir) ls.rec(base).toIterator.filter(_.ext == "class").map(_.relativeTo(base).toString)
    else {
      val zip = new ZipInputStream(new FileInputStream(base.toIO))
      Iterator.continually(zip.getNextEntry).takeWhile(_ != null).map(_.getName).filter(_.endsWith(".class"))
    }
  }
  def runTests(cl: ClassLoader, framework: Framework, classpath: Seq[Path]) = {


    val fingerprints = framework.fingerprints()
    val testClasses = classpath.flatMap { base =>
      listClassFiles(base).flatMap { path =>
        val cls = cl.loadClass(path.stripSuffix(".class").replace('/', '.'))
        fingerprints.find {
          case f: SubclassFingerprint =>
            cl.loadClass(f.superclassName()).isAssignableFrom(cls)
          case f: AnnotatedFingerprint =>
            cls.isAnnotationPresent(
              cl.loadClass(f.annotationName()).asInstanceOf[Class[Annotation]]
            )
        }.map { f => (cls, f) }
      }
    }
    testClasses
  }
  def main(args: Array[String]): Unit = {
    val result = apply(
      frameworkName = args(0),
      entireClasspath = args(1).split(" ").map(Path(_)),
      testClassfilePath = args(2).split(" ").map(Path(_)),
      args = args(3) match{ case "" => Nil case x => x.split(" ").toList }
    )(new LogCtx {
      def log = new PrintLogger(true)
    })
    val outputPath = args(4)
    ammonite.ops.write(Path(outputPath), upickle.default.write(result))

    // Tests are over, kill the JVM whether or not anyone's threads are still running
    // Always return 0, even if tests fail. The caller can pick up the detailed test
    // results from the outputPath
    System.exit(0)
  }
  def apply(frameworkName: String,
            entireClasspath: Seq[Path],
            testClassfilePath: Seq[Path],
            args: Seq[String])
           (implicit ctx: LogCtx): Option[String] = {
    val outerClassLoader = getClass.getClassLoader
    val cl = new URLClassLoader(
      entireClasspath.map(_.toIO.toURI.toURL).toArray,
      ClassLoader.getSystemClassLoader().getParent()){
      override def findClass(name: String) = {
        if (name.startsWith("sbt.testing.")){
          outerClassLoader.loadClass(name)
        }else{
          super.findClass(name)
        }
      }
    }

    val framework = cl.loadClass(frameworkName)
      .newInstance()
      .asInstanceOf[sbt.testing.Framework]

    val testClasses = runTests(cl, framework, testClassfilePath)

    val runner = framework.runner(args.toArray, args.toArray, cl)

    val tasks = runner.tasks(
      for((cls, fingerprint) <- testClasses.toArray)
      yield {
        new TaskDef(cls.getName.stripSuffix("$"), fingerprint, true, Array())
      }
    )
    val events = mutable.Buffer.empty[Status]
    for(t <- tasks){
      t.execute(
        new EventHandler {
          def handle(event: Event) = events.append(event.status())
        },
        Array(
          new Logger {
            def debug(msg: String) = ctx.log.info(msg)

            def error(msg: String) = ctx.log.error(msg)

            def ansiCodesSupported() = true

            def warn(msg: String) = ctx.log.info(msg)

            def trace(t: Throwable) = t.printStackTrace(ctx.log.outputStream)

            def info(msg: String) = ctx.log.info(msg)
        })
      )
    }
    val doneMsg = runner.done()
    val msg =
      if (doneMsg.trim.nonEmpty) doneMsg
      else{
        val grouped = events.groupBy(x => x).mapValues(_.length).filter(_._2 != 0).toList.sorted
        grouped.map{case (k, v) => k + ": " + v}.mkString(",")
      }
    ctx.log.info(msg)
    if (events.count(Set(Status.Error, Status.Failure)) == 0) None
    else Some(msg)
  }
}
