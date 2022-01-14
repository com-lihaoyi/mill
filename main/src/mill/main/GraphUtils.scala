package mill.main

import mill.define.ScriptNode

import scala.collection.mutable

private object GraphUtils {
  def linksToScriptNodeGraph(links: Map[String, Seq[String]]): Seq[ScriptNode] = {
    val cache = mutable.Map.empty[String, ScriptNode]

    def scriptNodeOf(key: String): ScriptNode = {
      cache.get(key) match {
        case Some(node) => node
        case None =>
          val node = ScriptNode(key, links(key).map(scriptNodeOf))
          cache(key) = node
          node
      }
    }

    links.keys.map(scriptNodeOf).toSeq
  }
}
