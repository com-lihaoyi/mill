object Main {
  def main(args: Array[String]): Unit = {
    assert(collection.immutable.ArraySeq(1).toString == "ArraySeq(1)")
    assert(scala.xml.Node.EmptyNamespace == "")
  }
}
