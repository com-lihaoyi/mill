scalaVersion := "2.12.3"

name := "hbt"

organization := "com.lihaoyi"


libraryDependencies += "com.lihaoyi" %% "sourcecode" % "0.1.4"

sourceGenerators in Compile += Def.task {
  val dir = (sourceManaged in Compile).value
  val file = dir/"fasterparser"/"SequencerGen.scala"
  // Only go up to 21, because adding the last element makes it 22
  val tuples = (2 to 21).map{ i =>
    val ts = (1 to i) map ("T" + _)
    val chunks = (1 to i) map { n =>
      s"t._$n"
    }
    val tsD = (ts :+ "D").mkString(",")
    s"""
          implicit def Sequencer$i[$tsD]: Sequencer[(${ts.mkString(", ")}), D, ($tsD)] =
            Sequencer0((t, d) => (${chunks.mkString(", ")}, d))
          """
  }
  val output = s"""
          package hbt
          trait SequencerGen[Sequencer[_, _, _]] extends LowestPriSequencer[Sequencer]{
            protected[this] def Sequencer0[A, B, C](f: (A, B) => C): Sequencer[A, B, C]
            ${tuples.mkString("\n")}
          }
          trait LowestPriSequencer[Sequencer[_, _, _]]{
            protected[this] def Sequencer0[A, B, C](f: (A, B) => C): Sequencer[A, B, C]
            implicit def Sequencer1[T1, T2]: Sequencer[T1, T2, (T1, T2)] = Sequencer0{case (t1, t2) => (t1, t2)}
          }
        """.stripMargin
  IO.write(file, output)
  Seq(file)
}