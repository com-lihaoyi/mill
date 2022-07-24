// build.sc

import $file.build_plugins
import $file.build_deps, build_deps.Deps
import $file.build_modules
import $file.build_eclipse
import $file.build_eclipse_bundles
import mill.api.Loose
import mill.scalalib.api.CompilationResult
import mill.testrunner.TestRunner

import scala.util.control.NonFatal
// generated with `cd thirdparty && mill writeGeneratedDepsMillSrc`
// you may need to uncomment the below line to generate
//import $file.thirdparty.out.writeGeneratedDepsMillSrc.dest.eclipse_bundles

///** Eclipse dependencies, generated with mill. See thirdparty project. */
import build_eclipse_bundles.{EclipseBundles => ED}

import java.nio.file.attribute.PosixFilePermission
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import coursier.{Dependency, MavenRepository, Organization}
import de.tobiasroeser.mill.aspectj._
import de.tobiasroeser.mill.osgi._
import de.tobiasroeser.mill.vcs.version._
import de.tobiasroeser.mill.jacoco.{JacocoReportModule, JacocoTestModule}
import mill._
import mill.api.{Ctx, Loose, PathRef, Result}
import mill.contrib.buildinfo.BuildInfo
import mill.define.{Command, Input, Persistent, Source, Sources, Target, Task, TaskModule}
import mill.modules.{Jvm, Util}
import mill.modules.Jvm.{createAssembly, createJar}
import mill.scalalib._
import mill.scalalib.publish.{Dependency => PDependency, _}
import os.Path
