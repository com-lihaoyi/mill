scalaVersion := "2.12.3"

name := "forge"

organization := "com.lihaoyi"

libraryDependencies += "com.lihaoyi" %% "utest" % "0.6.0" % "test"

testFrameworks += new TestFramework("forge.Framework")

parallelExecution in Test := false

libraryDependencies ++= Seq(
  "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided",
  "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
  "com.lihaoyi" %% "sourcecode" % "0.1.4",
  "com.lihaoyi" %% "pprint" % "0.5.3",
  "com.lihaoyi" %% "ammonite-ops" % "1.0.2",
  "com.typesafe.play" %% "play-json" % "2.6.6"
)

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
          package forge
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
