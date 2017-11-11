package mill
import java.io.FileInputStream

import ammonite.ops._
import java.lang.annotation.Annotation
import java.util.zip.ZipInputStream

import sbt.testing._

object TestMain {
  def listClassFiles(base: Path): Iterator[String] = {
    if (base.isDir) ls.rec(base).toIterator.filter(_.ext == "class").map(_.relativeTo(base).toString)
    else {
      val zip = new ZipInputStream(new FileInputStream(base.toIO))
      Iterator.continually(zip.getNextEntry).takeWhile(_ != null).map(_.getName).filter(_.endsWith(".class"))
    }
  }
  def runTests(framework: Framework,
               targets: Seq[Path]) = {


    val fingerprints = framework.fingerprints()
    val testClasses = targets.flatMap { base =>
      listClassFiles(base).flatMap { path =>
        val cls = Class.forName(path.stripSuffix(".class").replace('/', '.'))
        fingerprints.find {
          case f: SubclassFingerprint =>
            Class.forName(f.superclassName()).isAssignableFrom(cls)
          case f: AnnotatedFingerprint =>
            cls.isAnnotationPresent(
              Class.forName(f.annotationName()).asInstanceOf[Class[Annotation]]
            )
        }.map { f => (cls, f) }
      }
    }
    testClasses
  }
  def main(args: Array[String]): Unit = {

    val framework = Class.forName("mill.UTestFramework")
      .newInstance()
      .asInstanceOf[sbt.testing.Framework]

    val testClasses = runTests(
      framework,
      Seq(pwd/'core/'target/"scala-2.12"/"test-classes")
    )

    pprint.log(testClasses)

    val runner = framework.runner(Array(), Array(), getClass.getClassLoader)
    println(runner)

    val tasks = runner.tasks(
      for((cls, fingerprint) <- testClasses.toArray)
      yield {
        new TaskDef(cls.getName.stripSuffix("$"), fingerprint, true, Array())
      }
    )
    for(t <- tasks){
      t.execute(
        new EventHandler {
          def handle(event: Event) = ()
        },
        Array(
          new Logger {
            def debug(msg: String) = println(msg)

            def error(msg: String) = println(msg)

            def ansiCodesSupported() = true

            def warn(msg: String) = println(msg)

            def trace(t: Throwable) = println(t)

            def info(msg: String) = println(msg)
        })
      )
    }
    val doneMsg = runner.done()
    if (doneMsg.trim.nonEmpty){
      println(doneMsg)
    }
  }
}
