package millbuild

import mill.javalib._
import mill.javalib.publish._

trait `9.0.0BaseModule` extends MavenModule with PublishModule {

  def javacOptions = super.javacOptions() ++
    Seq("-source", "21", "-target", "21")

  def pomSettings = PomSettings(
    "",
    "org.gradle.sample",
    "http://www.example.com/library",
    Seq(License(
      "",
      "The Apache License, Version 2.0",
      "http://www.apache.org/licenses/LICENSE-2.0.txt",
      false,
      false,
      ""
    )),
    VersionControl(
      Some("http://example.com/my-library/"),
      Some("scm:git:git://example.com/my-library.git"),
      Some("scm:git:ssh://example.com/my-library.git"),
      None
    ),
    Seq(Developer("johnd", "John Doe", "", None, None))
  )

  def publishVersion = "unspecified"

  def publishProperties = super.publishProperties() ++
    Map(("myProp", "value"), ("prop.with.dots", "anotherValue"))

  def jvmId = "zulu:21"

}
