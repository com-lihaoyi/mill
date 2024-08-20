package mill.playlib

trait PlayTestSuite {

  val testScala212 = sys.props.getOrElse("TEST_SCALA_2_12_VERSION", ???)
  val testScala213 = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
  val testScala3 = sys.props.getOrElse("TEST_SCALA_3_3_VERSION", ???)

  val testPlay26 = sys.props.getOrElse("TEST_PLAY_VERSION_2_6", ???)
  val testPlay27 = sys.props.getOrElse("TEST_PLAY_VERSION_2_7", ???)
  val testPlay28 = sys.props.getOrElse("TEST_PLAY_VERSION_2_8", ???)
  val testPlay29 = sys.props.getOrElse("TEST_PLAY_VERSION_2_9", ???)
  val testPlay30 = sys.props.getOrElse("TEST_PLAY_VERSION_3_0", ???)

  val matrix = Seq(
    (testScala212, testPlay26),
    (testScala212, testPlay27),
    (testScala213, testPlay27),
    (testScala213, testPlay28),
    (testScala213, testPlay29),
    (testScala3, testPlay29),
    (testScala213, testPlay30),
    (testScala3, testPlay30)
  )

  def skipUnsupportedVersions(playVersion: String)(test: => Unit) = playVersion match {
    case s"2.$minor.$_" if minor.toIntOption.exists(_ < 9) => test
    case _ if scala.util.Properties.isJavaAtLeast(11) => test
    case _ => System.err.println(s"Skipping since play $playVersion doesn't support Java 8")
  }

  def resourcePath: os.Path
}
