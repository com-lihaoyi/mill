package mill.scripts

/** Returns the shell command to run mill wrapper script. */
def millCmd: Seq[String] =
  if (!scala.util.Properties.isWin) Seq(sys.env("MILL_TEST_SH_SCRIPT"))
  else Seq("cmd.exe", "/c", sys.env("MILL_TEST_BAT_SCRIPT"))
