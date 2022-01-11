package mill.define

case class ScriptNode(cls: String, inputs: Seq[ScriptNode]) extends GraphNode[ScriptNode]
