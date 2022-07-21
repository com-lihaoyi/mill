package mill.buildfile

import scala.collection.mutable

object AmmoniteParser {

  def parseAddionalAmmoniteImports(parsedMillSetup: ParsedMillSetup) = {
    val queue = mutable.Queue.empty[os.Path]
    queue.enqueueAll(parsedMillSetup.buildScript.toSeq ++ parsedMillSetup.includedSourceFiles)

    val seenFiles = mutable.Set.empty[os.Path]
    val newDirectives = mutable.Seq.empty[MillUsingDirective]

    while (queue.nonEmpty) {
      val file = queue.dequeue()
      val MatchDep = """^import [$]ivy[.]`([^`]+)`""".r
      val MatchFile = """^import [$]file[.]([^,]+)""".r
      val directives = os.read.lines(file).collect {
        case MatchDep(dep) => MillUsingDirective.Dep(dep, file)
        case MatchFile(include) =>
          val pat = include.split("[.]").map {
            case "^" => os.up
            case x => os.rel / x
          }
          val incFile0: os.Path = file / os.up / pat
          val incFile = incFile0 / os.up / s"${incFile0.last}.sc"

          if(!seenFiles.contains(incFile)) {
            seenFiles.add(incFile)
            queue.enqueue(incFile)
          }

          MillUsingDirective.File(incFile.toString(), file)
      }
      newDirectives.appendedAll(directives)
    }

    parsedMillSetup.copy(directives = (parsedMillSetup.directives ++ newDirectives.toList).distinct)
  }

}
