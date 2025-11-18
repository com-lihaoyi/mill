package mill.javalib.spring.boot.worker.impl

import mill.api.TaskCtx
import mill.javalib.spring.boot.worker.SpringBootTools
import org.springframework.boot.loader.tools.*
import os.Path

class SpringBootToolsImpl() extends SpringBootTools {

  override def findSpringBootApplicationClass(classesPaths: Seq[os.Path])
      : Either[String, String] = {
    val candidates = classesPaths.distinct.filter(os.exists).map { classesPath =>
      Option(
        MainClassFinder.findSingleMainClass(
          classesPath.toIO,
          "org.springframework.boot.autoconfigure.SpringBootApplication"
        )
      )
    }

    candidates.flatten.distinct match {
      case Seq() => Left("Could not find a SpringBootApplication main class.")
      case Seq(single) => Right(single)
      case multi =>
        Left(
          s"Unable to find a single main class from the following candidates: ${multi.mkString(", ")}"
        )
    }
  }

  override def repackageJar(
      dest: Path,
      base: Path,
      mainClass: String,
      libs: Seq[Path],
      launchScript: Option[String]
  )(using ctx: TaskCtx): Unit = {
    val repack = new Repackager(base.toIO)
    repack.setMainClass(mainClass)

    val libraries: Libraries = { libCallback =>
      libs.foreach { lib =>
        libCallback.library(new Library(lib.toIO, LibraryScope.RUNTIME))
      }
    }

    repack.repackage(
      dest.toIO,
      libraries,
      launchScript.filter(_.nonEmpty) match {
        case Some(script) => () => script.getBytes()
        case _ => null
      }: LaunchScript
    )

    ()
  }

  override def springBootProcessAOT(
      classPath: Seq[Path],
      applicationMainClass: String,
      sourceOut: Path,
      resourceOut: Path,
      classOut: Path,
      groupId: String,
      artifactId: String,
      applicationArgs: String*
  ): Unit = {
    val process = mill.util.Jvm.spawnProcess(
      classPath = classPath,
      mainClass = "org.springframework.boot.SpringApplicationAotProcessor",
      mainArgs = Seq(
        applicationMainClass,
        sourceOut.toString,
        resourceOut.toString,
        classOut.toString,
        groupId,
        artifactId
      ) ++ applicationArgs
    )
    process.stdout.buffered.lines().forEach(println)
    process.stderr.buffered.lines().forEach(System.err.println(_))
    process.waitFor()
  }

  override def close(): Unit = {
    // Nothing to do
  }
}
