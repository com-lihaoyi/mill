package mill.define

case class ScriptNode(cls: String, inputs: Seq[ScriptNode]) extends GraphNode[ScriptNode] {
  private def copy(cls: String = cls, inputs: Seq[ScriptNode] = inputs): ScriptNode =
    new ScriptNode(cls, inputs)
}

object ScriptNode {
  private def unapply(scriptNode: ScriptNode): Option[(String, Seq[ScriptNode])] =
    Some(scriptNode.cls, scriptNode.inputs)
}
