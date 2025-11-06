package millbuild

import mill.api.*
import mill.api.opts.*
import mill.javalib.*
import mill.javalib.publish.*

trait ProjectPublishModule extends PublishModule with ProjectBaseModule {

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

}
