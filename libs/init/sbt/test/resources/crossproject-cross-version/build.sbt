val sharedSettings = Seq(
  crossScalaVersions := Seq("2.12.20", "2.13.14", "3.7.1"),
  scalacOptions := {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 12)) => Seq(
          "-deprecation",
          "-Xlint:_,-unused",
          "-Ywarn-numeric-widen",
          "-Ywarn-unused:_,-nowarn,-privates"
        )
      case Some((2, 13)) => Seq(
          "-deprecation",
          "-Xlint:_,-unused",
          "-Wnumeric-widen",
          "-Wunused"
        )
      case Some((3, _)) => Seq(
          "-deprecation",
          "-Wunused"
        )
      case _ => Nil
    }
  },
  libraryDependencies += "com.lihaoyi" %%% "upickle" % "4.3.0"
)

lazy val jvmUtil = project.in(file("jvm-util")).settings(sharedSettings)

lazy val pure = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .settings(sharedSettings)

lazy val full = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .settings(sharedSettings)

lazy val fullJVM = full.jvm.dependsOn(jvmUtil)

lazy val dummy = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Dummy)
  .settings(sharedSettings)
