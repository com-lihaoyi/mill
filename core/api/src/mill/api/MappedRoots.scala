package mill.api

import mill.api.internal.NamedParameterOnlyDummy
import mill.constants.PathVars

import scala.annotation.unused
import scala.util.DynamicVariable

type MappedRoots = Seq[(key: String, path: os.Path)]

object MappedRoots extends MappedRootsImpl

trait MappedRootsImpl {

  private val rootMapping: DynamicVariable[MappedRoots] = DynamicVariable(Seq())

  def get: MappedRoots = rootMapping.value

  def toMap: Map[String, os.Path] = get.map(m => (m.key, m.path)).toMap

  def withMillDefaults[T](
      @unused t: NamedParameterOnlyDummy = new NamedParameterOnlyDummy,
      outPath: os.Path,
      workspacePath: os.Path = BuildCtx.workspaceRoot,
      homePath: os.Path = os.home
  )(thunk: => T): T = withMapping(
    Seq(
      ("MILL_OUT", outPath),
      ("WORKSPACE", workspacePath),
      // TODO: add coursier here
      ("HOME", homePath)
    )
  )(thunk)

  def withMapping[T](mapping: MappedRoots)(thunk: => T): T = withMapping(_ => mapping)(thunk)

  def withMapping[T](mapping: MappedRoots => MappedRoots)(thunk: => T): T = {
    val newMapping = mapping(rootMapping.value)
    var seenKeys = Set[String]()
    var seenPaths = Set[os.Path]()
    newMapping.foreach { case m =>
      require(!m.key.startsWith("$"), "Key must not start with a `$`.")
      require(m.key != PathVars.ROOT, s"Invalid key, '${PathVars.ROOT}' is a reserved key name.")
      require(
        !seenKeys.contains(m.key),
        s"Key must be unique, but '${m.key}' was given multiple times."
      )
      require(
        !seenPaths.contains(m.path),
        s"Paths must be unique, but '${m.path}' was given multiple times."
      )
      seenKeys += m.key
      seenPaths += m.path
    }
    rootMapping.withValue(newMapping)(thunk)
  }

  def encodeKnownRootsInPath(p: os.Path): String = {
    MappedRoots.get.collectFirst {
      case rep if p.startsWith(rep.path) =>
        s"$$${rep.key}${
            if (p != rep.path) {
              s"/${p.subRelativeTo(rep.path).toString()}"
            } else ""
          }"
    }.getOrElse(p.toString)
  }

  def decodeKnownRootsInPath(encoded: String): String = {
    if (encoded.startsWith("$")) {
      val offset = 1 // "$".length
      MappedRoots.get.collectFirst {
        case mapping if encoded.startsWith(mapping.key, offset) =>
          s"${mapping.path.toString}${encoded.substring(mapping.key.length + offset)}"
      }.getOrElse(encoded)
    } else {
      encoded
    }
  }

  /**
   * Use this to assert at runtime, that a root path with the given `key` is defined.
   * @throws NoSuchElementException when no path was mapped under the given `key`.
   */
  def requireMappedPaths(key: String*): Unit = {
    val map = toMap
    for {
      singleKey <- key
    } {
      if (!map.contains(singleKey)) throw new NoSuchElementException(s"No root path mapping defined for '${key}'")
    }
  }

}
