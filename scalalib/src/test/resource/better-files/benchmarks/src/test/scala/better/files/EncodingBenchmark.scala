package better.files

import java.nio.charset.Charset

import scala.util.Random

class EncodingBenchmark extends Benchmark {

  def testWrite(file: File, charset: Charset) = profile {
    for {
      writer <- file.bufferedWriter(charset)
      content <- Iterator.continually(Random.nextString(10000)).take(1000)
    } writer.write(content + "\n")
  }

  def testRead(file: File, charset: Charset) = profile {
    for {
      reader <- file.bufferedReader(charset)
      line <- reader.lines().autoClosed
    } line
  }

  def run(charset: Charset) = {
    File.temporaryFile() foreach {file =>
      val (_, w) = testWrite(file, charset)
      info(s"Charset=$charset, write=$w ms")

      val (_, r) = testRead(file, charset)
      info(s"Charset=$charset, read=$r ms")
    }
  }

  test("encoding") {
    val utf8 = Charset.forName("UTF-8")
    run(charset = utf8)
    info("-------------")
    run(charset = UnicodeCharset(utf8))
  }
}
