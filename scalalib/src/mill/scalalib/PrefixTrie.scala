package mill.scalalib

import scala.collection.mutable

private[scalalib] final class PrefixTrie[A](using CanEqual[A, A]) {
  // Node class to represent each node in the trie
  private class Node {
    // Map to store child nodes, where the key is the element and value is the Node
    val children: mutable.Map[A, Node] = mutable.Map.empty
  }

  // Root node of the trie
  private val root: Node = new Node()

  /**
   * Inserts a sequence into the trie.
   *
   * @param seq The sequence to insert
   */
  def insert(seq: Seq[A]): Unit = {
    var current = root

    // Traverse the trie, creating new nodes as needed
    for (elem <- seq) {
      current = current.children.getOrElseUpdate(elem, new Node())
    }
  }

  /**
   * Checks if the trie contains any sequence that starts with the given prefix.
   *
   * @param prefix The prefix to search for
   * @return true if any sequence starts with the prefix, false otherwise
   */
  def contains(prefix: Seq[A]): Boolean = {
    var current = root

    for (elem <- prefix) {
      current.children.get(elem) match {
        case Some(node) => current = node
        case None => return false
      }
    }

    true
  }

  /**
   * Clears all sequences from the trie by performing a deep clear.
   * This recursively clears all nodes to ensure proper cleanup.
   */
  def clear(): Unit = {
    // Helper function to recursively clear all nodes
    def deepClear(node: Node): Unit = {
      // First recursively clear all children
      for ((_, childNode) <- node.children) {
        deepClear(childNode)
      }
      // Then clear the children map of this node
      node.children.clear()
    }

    // Start the deep clear from the root
    deepClear(root)
  }
}
