
The modules (aka plugins) in this section are developed/maintained outside the mill git tree.

Besides the documentation provided here, we urge you to consult the respective linked plugin documentation pages.
The usage examples given here are most probably incomplete and sometimes outdated.

If you develop or maintain a mill plugin, please create a [pull request](https://github.com/lihaoyi/mill/pulls) to get your plugin listed here.

[comment]: # (Please keep list of plugins in alphabetical order)

## AspectJ

[AspectJ compiler](https://projects.eclipse.org/projects/tools.aspectj) support for mill.

Project home: https://github.com/lefou/mill-aspectj

### Quickstart

```scala
import mill._
import mill.scalalib._
import mill.define._

// Load the plugin from Maven Central via ivy/coursier
import $ivy.`de.tototec::de.tobiasroeser.mill.aspectj:0.1.0`, de.tobiasroeser.mill.aspectj._

object main extends AspectjModule {

  // Select the AspectJ version
  def aspectjVersion = T{ "{aspectjVersion}" }

  // Set AspectJ options, e.g. the language level and annotation processor
  // Run `mill main.ajcHelp` to get a list of supported options
  def ajcOptions = Seq("-8", "-proc:none")

}
```

### Configuration

Your module needs to extend `de.tobiasroeser.mill.aspectj.AspectjModule` which itself extends `mill.scalalib.JavaModule`.

The module trait `de.tobiasroeser.mill.aspectj.AspectjModule` has various configuration options (over those from `mill.scalalib.JavaModule`).

The most essential targets are:

* `def aspectjVersion: T[String]` - The AspectJ version. _Required_.
For a list of available releases refer to the [AspectJ Download Page](https://www.eclipse.org/aspectj/downloads.php).

* `def ajcOptions: T[Seq[String]]` - Additional options to be used by `ajc` in the `compile` target.

* `def compile: T[CompilationResult]` - Compiles the source code with the ajc compiler.

For a complete list of configuration options and more documentation, please refer to the [project home page](https://github.com/lefou/mill-aspectj).

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

Project home: https://github.com/davoclavo/mill-ensime

### Quickstart

```scala
import mill._
interp.repositories() =
  interp.repositories() ++ Seq(coursier.MavenRepository("https://jitpack.io"))

@

import $ivy.`com.github.yyadavalli::mill-ensime:0.0.2`
```

You can then run the following to generate the .ensime file

```sh
mill fun.valycorp.mill.GenEnsime/ensimeConfig
```

Optionally, you can specify the ensime server version using the --server flag like

```sh
mill fun.valycorp.mill.GenEnsime/ensimeConfig --server "3.0.0-SNAPSHOT"
```

## Git

A git version plugin for mill.

Project home: https://github.com/joan38/mill-git

*build.sc*:
```scala
import $ivy.`com.goyeau::mill-git:<latest version>`
import com.goyeau.mill.git.GitVersionedPublishModule
import mill.scalalib.JavaModule
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}

object `jvm-project` extends JavaModule with GitVersionedPublishModule {
  override def pomSettings = PomSettings(
    description = "JVM Project",
    organization = "com.goyeau",
    url = "https://github.com/joan38/mill-git",
    licenses = Seq(License.MIT),
    versionControl = VersionControl.github("joan38", "mill-git"),
    developers = Seq(Developer("joan38", "Joan Goyeau", "https://github.com/joan38"))
  )
}
```

```shell script
> mill show jvm-project.publishVersion
[1/1] show 
[2/2] com.goyeau.mill.git.GitVersionModule.version 
"0.0.0-470-6d0b3d9"
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

## JBuildInfo

This is a [mill](https://www.lihaoyi.com/mill/) module similar to 
[BuildInfo](https://www.lihaoyi.com/mill/page/contrib-modules.html#buildinfo)
but for Java. 
It will generate a Java class containing information from your build.

Project home: https://github.com/carueda/mill-jbuildinfo

To declare a module that uses this plugin, extend the
`com.github.carueda.mill.JBuildInfo` trait and provide
the desired information via the `buildInfoMembers` method:

```scala
// build.sc
import $ivy.`com.github.carueda::jbuildinfo:0.1.2`
import com.github.carueda.mill.JBuildInfo
import mill.T

object project extends JBuildInfo {
  def buildInfoMembers: T[Map[String, String]] = T {
    Map(
      "name" -> "some name",
      "version" -> "x.y.z"
    )
  }
}
```

This will generate:

```java
// BuildInfo.java
public class BuildInfo {
  public static final String getName() { return "some name"; }
  public static final String getVersion() { return "x.y.z"; }
}
```

### Configuration options

* `def buildInfoMembers: T[Map[String, String]]`

    The map containing all member names and values for the generated class.

* `def buildInfoClassName: String`, default: `BuildInfo`

    The name of the class that will contain all the members from
    `buildInfoMembers`.

* `def buildInfoPackageName: Option[String]`, default: `None`
  
    The package name for the generated class.


## Kotlin

[Kotlin](https://kotlinlang.org/) compiler support for mill.

Project home: https://github.com/lefou/mill-kotlin

### Quickstart

```scala
import mill._
import mill.scalalib._
import mill.define._

// Load the plugin from Maven Central via ivy/coursier
import $ivy.`de.tototec::de.tobiasroeser.mill.kotlin:0.0.1`, de.tobiasroeser.mill.kotlin._

object main extends KotlinModule {

  // Select the Kotlin version
  def kotlinVersion = T{ "{kotlinVersion}" }

  // Set additional Kotlin compiler options, e.g. the language level and annotation processor
  // Run `mill main.kotlincHelp` to get a list of supported options
  def kotlincOptions = Seq("-verbose")

}
```

### Documentation 

For documentation please visit the [mill-kotlin project page](https://github.com/lefou/mill-kotlin).

You will find there also a version compatibility matrix.


## Mill Wrapper Scripts

Small script to automatically fetch and execute mill build tool.

Project home: https://github.com/lefou/millw

### How it works

`millw` is a small wrapper script around mill and works almost identical to mill. It automatically downloads a mill release into `$HOME/.mill/download`.

The mill version to be used will be determined by the following steps. The search ends, after the first step that results in a version.

* If the first parameter is `--mill-version`, the second parameter will be used as the mill version.
  Example

  ```
  sh $ mill --mill-version 0.3.6 --disable-ticker version
  0.3.6
  ```

* If there is a file `.mill-version` in the working directory, it’s content will be used as mill version. The file must have only a mill version as content, no additional content or comments are supported.
  Example

  ```
  sh $ echo -n "0.3.6" > .mill-version
  sh $ mill --disable-ticker version
  0.3.6
  ```

  The values of the `DEFAULT_MILL_VERSION` variable inside the script will be used.



### Use cases

#### As mill executable

Istead of installing mill, you can just place the script into you local `$HOME/bin` directory and rename it to `mill`.

If you need a special mill version in a project directory, just place a `.mill-version` file with the best mill version.
Example: setting mill 0.3.6 as best local mill version

```
sh $ echo -n "0.3.6" > .mill-version
```

#### As a wrapper script in your project

To make the start for others easier or to always have the correct mill version in your CI environment, you can just place a copy of the script as `millw` in your project root directory.

You should change the `DEFAULT_MILL_VERSION` variable in that script to the correct version you want to use and add the file under version control.


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

_Since Mill `0.6.1-27-f265a4` there is a built-in `publishM2Local` target in `PublishModule`._

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

## Scalafix

[Scalafix](https://scalacenter.github.io/scalafix/) support for mill.

Project home: https://github.com/joan38/mill-scalafix

### Fix sources

*build.sc*:
```scala
import $ivy.`com.goyeau::mill-scalafix:<latest version>`
import com.goyeau.mill.scalafix.ScalafixModule
import mill.scalalib._

object project extends ScalaModule with ScalafixModule {
  def scalaVersion = "2.12.11"
}
```

```shell script
> mill project.fix
[29/29] project.fix
/project/project/src/MyClass.scala:12:11: error: [DisableSyntax.var] mutable state should be avoided
  private var hashLength = 7
          ^^^
1 targets failed
project.fix A Scalafix linter error was reported
```
