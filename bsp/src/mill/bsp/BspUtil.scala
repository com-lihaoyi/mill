package mill.bsp

private[bsp] trait BspUtil {
  def pretty = pprint.PPrinter(defaultHeight = 10000)
}

object BspUtil extends BspUtil
