package jawn
package parser

import java.io._

object TestUtil {
  def withTemp[A](s: String)(f: File => A): A = {
    val t = File.createTempFile("jawn-syntax", ".json")
    val pw = new PrintWriter(t)
    pw.println(s)
    pw.close()
    try {
      f(t)
    } finally {
      t.delete()
    }
  }
}
