import shared.Utils

object Lib {
  def parse(s: String): Seq[String] = s.split(":").toSeq
  def addTwice(a: Int, b: Int) = Utils.add(a, b) + Utils.add(a, b)
  def vmName = sys.props("java.vm.name")

}
