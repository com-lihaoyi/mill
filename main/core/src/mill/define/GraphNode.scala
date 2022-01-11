package mill.define

trait GraphNode[T] {
  /**
   * What other nodes does this node depend on?
   */
  def inputs: Seq[T]
}
