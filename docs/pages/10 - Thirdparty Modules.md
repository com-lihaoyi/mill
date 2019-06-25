
The modules (aka plugins) in this section are developed/maintained outside the mill git tree.

Besides the documentation provided here, we urge you to consult the respective linked plugin documentation pages.
The usage examples given here are most probably incomplete and sometimes outdated.

If you develop or maintain a mill plugin, please create a [pull request](https://github.com/lihaoyi/mill/pulls) to get your plugin listed here.

[comment]: # (Please keep list of plugins in alphabetical order)

## Bash Completion

Limited bash completion support.

Project home: https://github.com/lefou/mill-bash-completion


## DGraph

Show transitive dependencies of your build in your browser.

Project home: https://github.com/ajrnz/mill-dgraph

### Quickstart

```scala
import $ivy.`com.github.ajrnz::mill-dgraph:0.2.0`
```

```sh
sh> mill plugin.dgraph.browseDeps(proj)()
```

## Ensime

Create an [.ensime](http://ensime.github.io/ "ensime") file for your build.

Project home: https://github.com/yyadavalli/mill-ensime

### Quickstart

```scala
import $ivy.`fun.valycorp::mill-ensime:0.0.1`
```

```sh
sh> mill fun.valycorp.mill.GenEnsime/ensimeConfig
```

## Integration Testing Mill Plugins

Integration testing for mill plugins.

### Quickstart

We assume, you have a mill plugin named `mill-demo`

```scala
// build.sc
import mill._, mill.scalalib._
object demo extends ScalaModule with PublishModule {
  // ...
}
```

Add an new test sub-project, e.g. `it`.

```scala
import $ivy.`de.tototec::de.tobiasroeser.mill.integrationtest:0.1.0`
import de.tobiasroeser.mill.integrationtest._

object it extends MillIntegrationTest {

  def millTestVersion = "{exampleMillVersion}"

  def pluginsUnderTest = Seq(demo)

}
```

Your project should now look similar to this:

```text
.
+-- demo/
|   +-- src/
|
+-- it/
    +-- src/
        +-- 01-first-test/
        |   +-- build.sc
        |   +-- src/
        |
        +-- 02-second-test/
            +-- build.sc
```

As the buildfiles `build.sc` in your test cases typically want to access the locally built plugin(s),
the plugins publishes all plugins referenced under `pluginsUnderTest` to a temporary ivy repository, just before the test is executed.
The mill version used in the integration test then used that temporary ivy repository.

Instead of referring to your plugin with `import $ivy.'your::plugin:version'`,
you can use the following line instead, which ensures you will use the correct locally build plugins.

```scala
// build.sc
import $exec.plugins
```

Effectively, at execution time, this line gets replaced by the content of `plugins.sc`, a file which was generated just before the test started to execute.

### Configuration and Targets

The mill-integrationtest plugin provides the following targets.

#### Mandatory configuration

* `def millTestVersion: T[String]`
  The mill version used for executing the test cases.
  Used by `downloadMillTestVersion` to automatically download.

* `def pluginsUnderTest: Seq[PublishModule]` -
  The plugins used in the integration test.
  You should at least add your plugin under test here.
  You can also add additional libraries, e.g. those that assist you in the test result validation (e.g. a local test support project).
  The defined modules will be published into a temporary ivy repository before the tests are executed.
  In your test `build.sc` file, instead of the typical `import $ivy.` line,
  you should use `import $exec.plugins` to include all plugins that are defined here.

#### Optional configuration

* `def sources: Sources` -
  Locations where integration tests are located.
  Each integration test is a sub-directory, containing a complete test mill project.

* `def testCases: T[Seq[PathRef]]` -
  The directories each representing a mill test case.
  Derived from `sources`.

* `def testTargets: T[Seq[String]]` -
  The targets which are called to test the project.
  Defaults to `verify`, which should implement test result validation.

* `def downloadMillTestVersion: T[PathRef]` -
  Download the mill version as defined by `millTestVersion`.
  Override this, if you need to use a custom built mill version.
  Returns the `PathRef` to the mill executable (must have the executable flag).

#### Commands

* `def test(): Command[Unit]` -
  Run the integration tests.


## JBake

Create static sites/blogs with JBake.

Plugin home: https://github.com/lefou/mill-jbake

JBake home: https://jbake.org

### Quickstart

```scala
// build.sc
import mill._
import $ivy.`de.tototec::de.tobiasroeser.mill.jbake:0.1.0`
import de.tobiasroeser.mill.jbake._

object site extends JBakeModule {

  def jbakeVersion = "2.6.4"

}
```

Generate the site:

```sh
bash> mill site.jbake
```

Start a local Web-Server on Port 8820 with the generated site:

```sh
bash> mill site.jbakeServe
```


## OSGi

Produce OSGi Bundles with mill.

Project home: https://github.com/lefou/mill-osgi

### Quickstart

```scala
import mill._, mill.scalalib._
import $ivy.`de.tototec::de.tobiasroeser.mill.osgi:0.0.5`
import de.tobiasroeser.mill.osgi._

object project extends ScalaModule with OsgiBundleModule {

  def bundleSymbolicName = "com.example.project"

  def osgiHeaders = T{ super.osgiHeaders().copy(
    `Export-Package`   = Seq("com.example.api"),
    `Bundle-Activator` = Some("com.example.internal.Activator")
  )}

  // other settings ...

}
```


## PublishM2

Mill plugin to publish artifacts into a local Maven repository.

Project home: https://github.com/lefou/mill-publishM2

### Quickstart

Just mix-in the `PublishM2Module` into your project.
`PublishM2Module` already extends mill's built-in `PublishModule`.

File: `build.sc`
```scala
import mill._, scalalib._, publish._

import $ivy.`de.tototec::de.tobiasroeser.mill.publishM2:0.0.1`
import de.tobiasroeser.mill.publishM2._

object project extends PublishModule with PublishM2Module {
  // ...
}
```

Publishing to default local Maven repository

```bash
> mill project.publishM2Local
[40/40] project.publishM2Local
Publishing to /home/user/.m2/repository
```

Publishing to custom local Maven repository

```bash
> mill project.publishM2Local /tmp/m2repo
[40/40] project.publishM2Local
Publishing to /tmp/m2repo
```

