package mill.define

case class ScriptNode(cls: String, inputs: Seq[ScriptNode], path: os.Path) extends GraphNode[ScriptNode]
