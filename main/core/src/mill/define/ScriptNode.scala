package mill.define

class ScriptNode(val cls: String, val inputs: Seq[ScriptNode]) extends GraphNode[ScriptNode]
