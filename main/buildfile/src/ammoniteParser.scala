package mill.buildfile

import mill.api.experimental

import java.util.regex.Pattern
import scala.collection.mutable
import scala.util.matching.Regex

@experimental
object AmmoniteParser {

  private val MatchDep = """^import ([$]ivy[.]`([^`]+)`).*""".r
  private val MatchFile = """^import ([$]file[.]([^,]+)).*""".r
  private val MatchShebang = """^(#!.*)$""".r

  def parseAmmoniteImports(parsedMillSetup: ParsedMillSetup): ParsedMillSetup = {
    val queue = mutable.Queue.empty[os.Path]
    queue.enqueueAll(parsedMillSetup.buildScript.toSeq ++ parsedMillSetup.includedSourceFiles)

    var seenFiles = Set.empty[os.Path]
    var newDirectives = Seq.empty[MillUsingDirective]

    while (queue.nonEmpty) {
      val file = queue.dequeue()
      val directives = os.read.lines(file).collect {
        case m @ MatchDep(_, dep) => MillUsingDirective.Dep(dep, file)
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

          MillUsingDirective.File(incFile.toString(), file)
      }

      newDirectives = newDirectives ++ directives
    }

    parsedMillSetup.copy(
      directives = (parsedMillSetup.directives ++ newDirectives).distinct
    )
  }

  def replaceAmmoniteImportsAndShebang(parsedMillSetup: ParsedMillSetup)
      : Map[os.Path, Seq[String]] = {
    (parsedMillSetup.buildScript.toSeq ++ parsedMillSetup.includedSourceFiles)
      .map(f => (f, f))
      .toMap.view.mapValues { file =>
        os.read.lines(file).map {
          case m @ MatchDep(full, dep) =>
            m.replaceFirst(Pattern.quote(full), "java.lang.Object")

          case m @ MatchFile(full, include) =>
            m.replaceFirst(Pattern.quote(full), "java.lang.Object")

          case m @ MatchShebang(full) =>
            m.replaceFirst(Pattern.quote(full), "// " + full)

          case x => x
        }
      }
      .toMap
  }

}
