val crossSettings = Seq(
  scalaVersion := "2.13.14",
  libraryDependencies ++= Seq(
    "com.lihaoyi" %%% "upickle" % "4.3.0",
    "com.lihaoyi" %%% "utest" % "0.9.1" % Test
  ),
  testFrameworks += new TestFramework("utest.runner.Framework")
)

lazy val pure = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .settings(crossSettings)
  .jvmSettings(libraryDependencies += "com.lihaoyi" %% "os-lib" % "0.11.5")

lazy val full = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .dependsOn(pure % "compile->compile;test->test")
  .settings(crossSettings)

lazy val dummy = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Dummy)
  .settings(crossSettings)
