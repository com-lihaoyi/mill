package mill.define

class ScriptNode(val cls: String, val inputs: Seq[ScriptNode]) extends GraphNode[ScriptNode]

object ScriptNode {
  def apply(cls: String, inputs: Seq[ScriptNode]): ScriptNode =
    new ScriptNode(cls, inputs)
}
