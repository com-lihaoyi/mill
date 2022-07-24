import $file.build_deps
import $file.build_plugins
import build_deps.Deps
import coursier.maven.MavenRepository
import de.tobiasroeser.mill.aspectj.AspectjModule
import de.tobiasroeser.mill.kotlin.KotlinModule
import io.github.classgraph.{ClassGraph, ResourceList}
import mill._
import mill.api.{Ctx, IO, Loose, PathRef}
import mill.define.{Module => _, _}
import mill.main.RunScript
import mill.modules.Jvm
import mill.scalalib.GenIdeaModule.{Element, JavaFacet}
import mill.scalalib._
import mill.scalalib.api.CompilationResult
import mill.scalalib.publish.{Artifact, LocalM2Publisher, Scope, Dependency => PDependency}
import os.{Path, SubPath}

import java.io.{FileOutputStream, InputStream}
import java.net.{URI, URL}
import java.nio.file
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.{FileSystems, Files, StandardOpenOption}
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.reflect.internal.util.ScalaClassLoader.URLClassLoader
import scala.util.Using
import scala.xml.{Elem, NodeSeq, PrettyPrinter}

