package mill.main.buildgen

object OptionNodeTreeUtils {
  def toTree[T](prefixDirs: Seq[String], dirs: List[String], node: Node[T]): Tree[Node[Option[T]]] =
    dirs match {
      case Nil => Tree(node.copy(value = Some(node.value)))
      case dir :: nextDirs =>
        Tree(Node(prefixDirs, None), Seq(toTree(prefixDirs :+ dir, nextDirs, node)))
    }

  def doMerge[T](
      tree: Tree[Node[Option[T]]],
      prefixDirs: Seq[String],
      dirs: List[String],
      node: Node[T]
  ): Tree[Node[Option[T]]] =
    dirs match {
      case Nil => tree.copy(node =
          tree.node.value.fold(node.copy(value = Some(node.value)))(existingProject =>
            throw new IllegalArgumentException(
              s"Project at duplicate locations: $existingProject and $node"
            )
          )
        )
      case dir :: nextDirs =>
        tree.copy(children = {
          def nextPrefixDirs = prefixDirs :+ dir

          tree.children.iterator.zipWithIndex.find(_._1.node.dirs.last == dir) match {
            case Some((childTree, index)) =>
              tree.children.updated(index, doMerge(childTree, nextPrefixDirs, nextDirs, node))
            case None => tree.children :+ toTree(nextPrefixDirs, nextDirs, node)
          }
        })
    }

  def merge[T](tree: Tree[Node[Option[T]]], node: Node[T]): Tree[Node[Option[T]]] =
    doMerge(tree, Seq.empty, node.dirs.toList, node)
}
