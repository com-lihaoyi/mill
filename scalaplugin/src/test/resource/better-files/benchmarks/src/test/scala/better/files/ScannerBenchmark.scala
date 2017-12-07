package better.files

import java.io.{BufferedReader, StringReader}

class ScannerBenchmark extends Benchmark {
  val file = File.newTemporaryFile()
  val n = 1000
  repeat(n) {
    file.appendLine(-n to n mkString " ")
      .appendLine("hello " * n)
      .appendLine("world " * n)
  }
  val scanners: Seq[BufferedReader => AbstractScanner] = Seq(
    new JavaScanner(_),
    new StringBuilderScanner(_),
    new CharBufferScanner(_),
    new StreamingScanner(_),
    new IterableScanner(_),
    new IteratorScanner(_),
    new BetterFilesScanner(_),
    new ArrayBufferScanner(_),
    new FastJavaIOScanner2(_),
    new FastJavaIOScanner(_)
  )

  def runTest(scanner: AbstractScanner) = {
    val (_, time) = profile(run(scanner))
    scanner.close()
    info(f"${scanner.getClass.getSimpleName.padTo(25, ' ')}: $time%4d ms")
  }

  def run(scanner: AbstractScanner): Unit = repeat(n) {
    assert(scanner.hasNext)
    val ints = List.fill(2 * n + 1)(scanner.nextInt())
    val line = "" //scanner.nextLine()
    val words = IndexedSeq.fill(2 * n)(scanner.next())
    (line, ints, words)
  }

  test("scanner") {
    info("Warming up ...")
    scanners foreach { scannerBuilder =>
      val canaryData =
        """
          |10 -23
          |Hello World
          |Hello World
          |19
        """.stripMargin
      val scanner = scannerBuilder(new BufferedReader(new StringReader(canaryData)))
      info(s"Testing ${scanner.getClass.getSimpleName} for correctness")
      assert(scanner.hasNext)
      assert(scanner.nextInt() == 10)
      assert(scanner.nextInt() == -23)
      assert(scanner.next() == "Hello")
      assert(scanner.next() == "World")
      val l = scanner.nextLine()
      assert(l == "Hello World", l)
      assert(scanner.nextInt() == 19)
      //assert(!scanner.hasNext)
    }

    info("Running benchmark ...")
    scanners foreach { scanner => runTest(scanner(file.newBufferedReader)) }
  }
}
