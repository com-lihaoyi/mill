package mill.main.buildgen

def toTree[T](prefixDirs: Seq[String], dirs: List[String], node: Node[T]): Tree[Node[Option[T]]] =
  dirs match
    case Nil => Tree(node.copy(value = Some(node.value)))
    case dir :: nextDirs =>
      Tree(Node(prefixDirs, None), Seq(toTree(prefixDirs :+ dir, nextDirs, node)))

def merge[T](
    tree: Tree[Node[Option[T]]],
    prefixDirs: Seq[String],
    dirs: List[String],
    node: Node[T]
): Tree[Node[Option[T]]] =
  dirs match
    case Nil => tree.copy(root =
        tree.root.value.fold(node.copy(value = Some(node.value)))(existingProject =>
          throw IllegalArgumentException(
            s"Project at duplicate locations: $existingProject and $node"
          )
        )
      )
    case dir :: nextDirs =>
      tree.copy(children = {
        def nextPrefixDirs = prefixDirs :+ dir
        tree.children.iterator.zipWithIndex.find(_._1.root.dirs.last == dir) match
          case Some((childTree, index)) =>
            tree.children.updated(index, merge(childTree, nextPrefixDirs, nextDirs, node))
          case None => tree.children :+ toTree(nextPrefixDirs, nextDirs, node)
      })

def merge[T](tree: Tree[Node[Option[T]]], node: Node[T]): Tree[Node[Option[T]]] =
  merge(tree, Seq.empty, node.dirs.toList, node)
