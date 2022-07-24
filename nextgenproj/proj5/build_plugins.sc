// Collection of all external dependencies used by mill for this project

// calculate the version from git version
import $ivy.`de.tototec::de.tobiasroeser.mill.vcs.version::0.1.4`
// generate OSGi manifests
import $ivy.`de.tototec::de.tobiasroeser.mill.osgi::0.4.0`
// Support for AspectJ compiler
import $ivy.`de.tototec::de.tobiasroeser.mill.aspectj::0.4.0`
// Generate scala classes with build-time info
import $ivy.`com.lihaoyi::mill-contrib-buildinfo:`
// Support for Kotlin compile
import $ivy.`de.tototec::de.tobiasroeser.mill.kotlin::0.2.2`
// used in our dependency analysis targets
import $ivy.`io.github.classgraph:classgraph:4.8.93`
// JaCoCo Code Coverage
import $ivy.`de.tototec::de.tobiasroeser.mill.jacoco::0.0.2`

