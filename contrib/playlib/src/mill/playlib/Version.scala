package mill
package playlib

private[playlib] trait Version extends Module{

  def playVersion: T[String]

  private[playlib] def playMinorVersion: T[String] = T {
    playVersion().split("\\.").take(2).mkString("", ".", ".0")
  }
}
