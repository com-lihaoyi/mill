package mill.javalib.spring.boot.worker.impl

import mill.javalib.spring.boot.worker.SpringBootTools
import org.springframework.boot.loader.tools.{
  LaunchScript,
  Libraries,
  Library,
  LibraryScope,
  MainClassFinder,
  Repackager
}
import os.Path
import mill.api.TaskCtx

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
}
