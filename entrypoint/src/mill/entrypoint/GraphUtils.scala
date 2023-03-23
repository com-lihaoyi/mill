package mill.entrypoint


import mill.define.ScriptNode

import scala.collection.mutable

@mill.api.internal
private object GraphUtils {
  def linksToScriptNodeGraph(links: collection.Map[String, (os.Path, Seq[String])]): Seq[ScriptNode] = {
    val cache = mutable.Map.empty[String, ScriptNode]

    def scriptNodeOf(key: String): ScriptNode = {
      cache.get(key) match {
        case Some(node) => node
        case None =>
          val (path, scriptLinks) = links(key)
          val node = ScriptNode(key, scriptLinks.map(scriptNodeOf), path)
          cache(key) = node
          node
      }
    }

    links.keys.map(scriptNodeOf).toSeq
  }
}
