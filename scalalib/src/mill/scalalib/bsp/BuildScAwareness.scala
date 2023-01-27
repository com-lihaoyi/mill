package mill.scalalib.bsp

import upickle.default.{ReadWriter, macroRW}

import scala.collection.mutable
import mill.api.JsonFormatters.pathReadWrite
import mill.api.{experimental, internal}

@internal
@experimental
trait BuildScAwareness {
  import BuildScAwareness._

  private val MatchDep = """^import ([$]ivy[.]`([^`]+)`).*""".r
  private val MatchFile = """^import ([$]file[.]([^,]+)).*""".r
  private val MatchShebang = """^(#!.*)$""".r

  def parseAmmoniteImports(buildScript: os.Path): Seq[Included] = {
    val queue = mutable.Queue.empty[os.Path]
    queue.enqueue(buildScript)

    var seenFiles = Set.empty[os.Path]
    var newInclusions = Seq.empty[Included]

    while (queue.nonEmpty) {
      val file = queue.dequeue()
      val inclusions = os.read.lines(file).collect {
        case m @ MatchDep(_, dep) => IncludedDep(dep, file)
        case m @ MatchFile(_, include) =>
          val pat = include.split("[.]").map {
            case "^" => os.up
            case x => os.rel / x
          }
          val incFile0: os.Path = file / os.up / pat
          val incFile = incFile0 / os.up / s"${incFile0.last}.sc"

          if (!seenFiles.contains(incFile)) {
            seenFiles += incFile
            queue.enqueue(incFile)
          }

          IncludedFile(incFile, file)
      }

      newInclusions = newInclusions ++ inclusions
    }

    newInclusions
  }
}

object BuildScAwareness extends BuildScAwareness {

  sealed trait Included
  object Included {
    implicit val upickleRW: ReadWriter[Included] = ReadWriter.merge(
      IncludedFile.upickleRW,
      IncludedDep.upickleRW
    )
  }

  case class IncludedFile(file: os.Path, includer: os.Path) extends Included
  object IncludedFile {
    implicit val upickleRW: ReadWriter[IncludedFile] = macroRW
  }

  case class IncludedDep(dep: String, includer: os.Path) extends Included
  object IncludedDep {
    implicit val upickleRW: ReadWriter[IncludedDep] = macroRW
  }

}
