val liveSets = Seq(400, 800, 1600, 3200, 6400)
val heapSizes = Seq(800, 1600, 3200, 6400, 12800)
val lists = for (liveSet <- liveSets) yield {
  for (heapSize <- heapSizes) yield {
    if (liveSet >= heapSize) ("", "")
    else {
      println(s"Benchmarking liveSet=$liveSet heapSize=$heapSize")
      val javaBin = os.Path(sys.env("JAVA_HOME")) / "bin"/"java"
      val res = os.proc(javaBin, s"-Xmx${heapSize}m", "-XX:+UseZGC", "GC.java", liveSet, 10000, 5)
        .call(check = false)

      res.out.lines().collectFirst { case s"longest-gc: $n, throughput: $m" => (n, m) }
        .getOrElse(sys.error(res.err.text()))
    }
  }
}

def renderTable(lists: Seq[Seq[String]]) = {
  def printRow(x: Seq[String]) = println(x.map(s => s"| $s ").mkString)
  printRow(Seq("live-set\\heap-size") ++ heapSizes.map(_ + " mb"))
  for ((liveSet, list) <- liveSets.zip(lists)) printRow(Seq(liveSet + " mb") ++ list)
}

renderTable(lists.map(_.map(_._1)))
renderTable(lists.map(_.map(_._2)))