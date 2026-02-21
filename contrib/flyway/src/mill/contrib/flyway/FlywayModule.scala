package mill.contrib.flyway

import mill.contrib.flyway.ConsoleLog.Level
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.flywaydb.core.api.logging.LogFactory
import org.flywaydb.core.internal.configuration.ConfigUtils as flyway

import scala.jdk.CollectionConverters.*
import mill.*
import mill.api.PathRef
import mill.api.daemon.MillURLClassLoader
import mill.javalib.{Dep, JavaModule}
import mill.javalib.DepSyntax
import org.flywaydb.core.api.output.{
  BaselineResult,
  CleanResult,
  MigrateOutput,
  MigrateResult,
  OperationResultBase
}

import java.lang.reflect.InvocationTargetException
import scala.annotation.nowarn

trait FlywayModule extends JavaModule {
  import FlywayModule.*

  def flywayUrl: T[String]
  def flywayUser: T[String] = Task { "" }
  def flywayPassword: T[String] = Task { "" }
  def flywayFileLocations: T[Seq[PathRef]] = Task {
    resources().map(pr => PathRef(pr.path / "db/migration", pr.quick))
  }
  def flywayPlaceholders: T[Map[String, String]] = Task {
    Map.empty[String, String]
  }

  def flywayDriverDeps: T[Seq[Dep]]
  def flywayPluginDeps: T[Seq[Dep]] = Task {
    Seq.empty
  }

  private lazy val bundledFlywayCoreDep: Dep = {
    val bundledVersion =
      Option(classOf[MigrateResult].getPackage.getImplementationVersion)
        .orElse(Option(classOf[MigrationVersion].getPackage.getImplementationVersion))
        .orElse {
          Option(classOf[MigrateResult].getProtectionDomain)
            .flatMap(pd => Option(pd.getCodeSource))
            .map(_.getLocation.getPath)
            .flatMap(extractFlywayVersionFromJarPath)
        }
        .getOrElse {
          throw new IllegalStateException(
            "Unable to detect bundled Flyway version from classpath"
          )
        }
    mvn"org.flywaydb:flyway-core:${bundledVersion}"
  }

  private def extractFlywayVersionFromJarPath(path: String): Option[String] =
    """.*flyway-core-([A-Za-z0-9+_.-]+)\.jar$""".r.findFirstMatchIn(path).map(_.group(1))

  def flywayRuntimeDeps: T[Seq[Dep]] = Task {
    flywayPluginDeps() ++ flywayDriverDeps()
  }

  def jdbcClasspath = Task {
    defaultResolver().classpath(flywayRuntimeDeps())
  }

  private def flywayClasspath = Task {
    defaultResolver().classpath(Seq(bundledFlywayCoreDep) ++ flywayRuntimeDeps())
  }

  private def strToOptPair[A](key: String, v: String) =
    Option(v)
      .filter(_.nonEmpty)
      .map(key -> _)

  def flywayClassloader: Worker[MillURLClassLoader] = Task.Worker {
    mill.util.Jvm.createClassLoader(flywayClasspath().map(_.path))
  }

  // Kept for compatibility with custom user tasks that call `flywayInstance` directly.
  // Built-in Flyway commands use the isolated loader path below so DB plugins from
  // `flywayPluginDeps` / `flywayDriverDeps` are discovered reliably.
  @nowarn("msg=.*Workers should implement AutoCloseable.*")
  def flywayInstance: Worker[Flyway] = Task.Worker {
    val jdbcClassloader = flywayClassloader()

    val configProps = Map(flyway.URL -> flywayUrl()) ++
      strToOptPair(flyway.USER, flywayUser()) ++
      strToOptPair(flyway.PASSWORD, flywayPassword())

    LogFactory.setLogCreator(new ConsoleLogCreator(Level.INFO))

    Flyway
      .configure(jdbcClassloader)
      .locations(flywayFileLocations().map("filesystem:" + _.path)*)
      .placeholders(flywayPlaceholders().asJava)
      .configuration(configProps.asJava)
      .cleanDisabled(false)
      .load
  }

  def flywayMigrate(): Command[MigrateResult] = Task.Command {
    val loader = flywayClassloader()
    val url = flywayUrl()
    withIsolatedFlyway(
      loader,
      url,
      flywayUser(),
      flywayPassword(),
      flywayFileLocations(),
      flywayPlaceholders()
    ) {
      (flyway, _, _) => toMigrateResult(callNoArg(flyway, "migrate"))
    }
  }

  def flywayClean(): Command[CleanResult] = Task.Command {
    val loader = flywayClassloader()
    val url = flywayUrl()
    withIsolatedFlyway(
      loader,
      url,
      flywayUser(),
      flywayPassword(),
      flywayFileLocations(),
      flywayPlaceholders()
    ) {
      (flyway, _, _) => toCleanResult(callNoArg(flyway, "clean"))
    }
  }

  def flywayBaseline(): Command[BaselineResult] = Task.Command {
    val loader = flywayClassloader()
    val url = flywayUrl()
    withIsolatedFlyway(
      loader,
      url,
      flywayUser(),
      flywayPassword(),
      flywayFileLocations(),
      flywayPlaceholders()
    ) {
      (flyway, _, _) => toBaselineResult(callNoArg(flyway, "baseline"))
    }
  }

  def flywayInfo(): Command[String] = Task.Command {
    val loader = flywayClassloader()
    val url = flywayUrl()
    withIsolatedFlyway(
      loader,
      url,
      flywayUser(),
      flywayPassword(),
      flywayFileLocations(),
      flywayPlaceholders()
    ) {
      (flyway, isolatedLoader, _) =>
        val info = callNoArg(flyway, "info")
        val current = callNoArg(info, "current")
        val currentSchemaVersion =
          if (current == null) MigrationVersion.EMPTY
          else callNoArg(current, "getVersion").toString

        val all = callNoArg(info, "all")
        val dumperClass =
          isolatedLoader.loadClass("org.flywaydb.core.internal.info.MigrationInfoDumper")
        val dumpMethod = dumperClass.getMethod("dumpToAsciiTable", all.getClass)
        val table = dumpMethod.invoke(null, all).asInstanceOf[String]

        val out =
          s"""Schema version: ${currentSchemaVersion}
             |${table}""".stripMargin
        Task.log.streams.out.println(out)
        out
    }
  }

  private[flyway] def flywayDetectedDatabaseType(): Command[String] = Task.Command {
    val loader = flywayClassloader()
    val url = flywayUrl()
    withIsolatedFlyway(
      loader,
      url,
      flywayUser(),
      flywayPassword(),
      flywayFileLocations(),
      flywayPlaceholders()
    ) {
      (flyway, flywayLoader, currentUrl) =>
        val conf = callNoArg(flyway, "getConfiguration")
        val databaseTypeRegisterClass =
          flywayLoader.loadClass("org.flywaydb.core.internal.database.DatabaseTypeRegister")
        val configurationClass =
          flywayLoader.loadClass("org.flywaydb.core.api.configuration.Configuration")
        val getDatabaseTypeForUrlMethod =
          databaseTypeRegisterClass.getMethod(
            "getDatabaseTypeForUrl",
            classOf[String],
            configurationClass
          )
        val databaseType =
          getDatabaseTypeForUrlMethod.invoke(
            null,
            currentUrl,
            conf.asInstanceOf[Object]
          ).asInstanceOf[AnyRef]
        callNoArg(databaseType, "getName").toString
    }
  }

  private def withIsolatedFlyway[T](
      loader: MillURLClassLoader,
      url: String,
      user: String,
      password: String,
      fileLocations: Seq[PathRef],
      placeholders: Map[String, String]
  )(
      f: (AnyRef, MillURLClassLoader, String) => T
  ): T = {
    val flyway = createIsolatedFlyway(
      loader,
      fileLocations.map(pr => s"filesystem:${pr.path}"),
      placeholders,
      configProps(url, user, password)
    )
    f(flyway, loader, url)
  }

  private def configProps(url: String, user: String, password: String): Map[String, String] =
    Map(flyway.URL -> url) ++
      strToOptPair(flyway.USER, user) ++
      strToOptPair(flyway.PASSWORD, password)

  private def createIsolatedFlyway(
      isolatedLoader: ClassLoader,
      locations: Seq[String],
      placeholders: Map[String, String],
      configProps: Map[String, String]
  ): AnyRef = {
    val flywayClass = isolatedLoader.loadClass("org.flywaydb.core.Flyway")
    val fluentConfiguration =
      callStatic(flywayClass, "configure", Seq(classOf[ClassLoader]), Seq(isolatedLoader))

    call(
      fluentConfiguration,
      "locations",
      Seq(classOf[Array[String]]),
      Seq(locations.toArray)
    )
    call(
      fluentConfiguration,
      "placeholders",
      Seq(classOf[java.util.Map[_, _]]),
      Seq(placeholders.asJava)
    )
    call(
      fluentConfiguration,
      "configuration",
      Seq(classOf[java.util.Map[_, _]]),
      Seq(configProps.asJava)
    )
    call(
      fluentConfiguration,
      "cleanDisabled",
      Seq(java.lang.Boolean.TYPE),
      Seq(Boolean.box(false))
    )

    callNoArg(fluentConfiguration, "load")
  }

  private def callNoArg(target: AnyRef, methodName: String): AnyRef = {
    call(target, methodName, Seq.empty, Seq.empty)
  }

  private def call(
      target: AnyRef,
      methodName: String,
      paramTypes: Seq[Class[?]],
      args: Seq[Any]
  ): AnyRef = {
    unwrapInvocation {
      target
        .getClass
        .getMethod(methodName, paramTypes*)
        .invoke(target, args.map(_.asInstanceOf[AnyRef])*)
        .asInstanceOf[AnyRef]
    }
  }

  private def callStatic(
      clazz: Class[?],
      methodName: String,
      paramTypes: Seq[Class[?]],
      args: Seq[Any]
  ): AnyRef = {
    unwrapInvocation {
      clazz
        .getMethod(methodName, paramTypes*)
        .invoke(null, args.map(_.asInstanceOf[AnyRef])*)
        .asInstanceOf[AnyRef]
    }
  }

  private def unwrapInvocation[T](f: => T): T =
    try f
    catch {
      case e: InvocationTargetException =>
        throw Option(e.getCause).getOrElse(e)
    }

  private def readFieldOption(target: AnyRef, fieldName: String): Option[AnyRef] =
    try Option(target.getClass.getField(fieldName).get(target).asInstanceOf[AnyRef])
    catch {
      case _: NoSuchFieldException => None
    }

  private def readStringField(target: AnyRef, fieldName: String): String =
    readFieldOption(target, fieldName).map(_.toString).orNull

  private def readIntField(target: AnyRef, fieldName: String): Int =
    readFieldOption(target, fieldName)
      .collect { case n: java.lang.Number => n.intValue }
      .getOrElse(0)

  private def readBooleanField(target: AnyRef, fieldName: String): Boolean =
    readFieldOption(target, fieldName)
      .collect { case b: java.lang.Boolean => b.booleanValue }
      .getOrElse(false)

  private def readStringSeqField(target: AnyRef, fieldName: String): Seq[String] =
    readFieldOption(target, fieldName).toSeq.flatMap {
      case iterable: java.lang.Iterable[?] =>
        iterable.iterator().asScala.toSeq.map {
          case null => null
          case x => x.toString
        }
      case x => Seq(x.toString)
    }

  private def readOperation(target: AnyRef): String =
    readFieldOption(target, "operation")
      .map(_.toString)
      .orElse {
        try Option(callNoArg(target, "getOperation")).map(_.toString)
        catch {
          case _: NoSuchMethodException => None
        }
      }
      .orNull

  private def toMigrateOutput(raw: AnyRef): MigrateOutput = {
    val out = new MigrateOutput()
    out.category = readStringField(raw, "category")
    out.version = readStringField(raw, "version")
    out.description = readStringField(raw, "description")
    out.`type` = readStringField(raw, "type")
    out.filepath = readStringField(raw, "filepath")
    out.executionTime = readIntField(raw, "executionTime")
    out.rolledBack = readFieldOption(raw, "rolledBack").collect { case b: java.lang.Boolean =>
      b
    }.orNull
    out
  }

  private def toMigrateResult(raw: AnyRef): MigrateResult = {
    val result = new MigrateResult()
    result.initialSchemaVersion = readStringField(raw, "initialSchemaVersion")
    result.targetSchemaVersion = readStringField(raw, "targetSchemaVersion")
    result.schemaName = readStringField(raw, "schemaName")
    result.migrations = readFieldOption(raw, "migrations")
      .collect { case iterable: java.lang.Iterable[?] =>
        iterable.iterator().asScala.toSeq.collect { case o: AnyRef => toMigrateOutput(o) }.asJava
      }
      .getOrElse(Seq.empty[MigrateOutput].asJava)
    result.migrationsExecuted = readIntField(raw, "migrationsExecuted")
    result.success = readBooleanField(raw, "success")
    result.flywayVersion = readStringField(raw, "flywayVersion")
    result.database = readStringField(raw, "database")
    result.databaseType = readStringField(raw, "databaseType")
    result.warnings = readStringSeqField(raw, "warnings").asJava
    result.setOperation(readOperation(raw))
    result
  }

  private def toCleanResult(raw: AnyRef): CleanResult = {
    val result =
      new CleanResult(readStringField(raw, "flywayVersion"), readStringField(raw, "database"))
    fillOperationResultBase(raw, result)
    result.schemasCleaned =
      new java.util.ArrayList[String](readStringSeqField(raw, "schemasCleaned").asJava)
    result.schemasDropped =
      new java.util.ArrayList[String](readStringSeqField(raw, "schemasDropped").asJava)
    result
  }

  private def toBaselineResult(raw: AnyRef): BaselineResult = {
    val result =
      new BaselineResult(readStringField(raw, "flywayVersion"), readStringField(raw, "database"))
    fillOperationResultBase(raw, result)
    result.successfullyBaselined = readBooleanField(raw, "successfullyBaselined")
    result.baselineVersion = readStringField(raw, "baselineVersion")
    result
  }

  private def fillOperationResultBase(raw: AnyRef, result: OperationResultBase): Unit = {
    result.operation = readOperation(raw)
    result.warnings = readStringSeqField(raw, "warnings").asJava
  }
}

object FlywayModule {

  implicit class JsonSupport(val string: String) extends AnyVal {
    def jsonify: ujson.Value = string match {
      case null => ujson.Null
      case x => ujson.Str(x)
    }
  }

  implicit def migrateOutputWriter: upickle.Writer[MigrateOutput] =
    upickle.writer[ujson.Obj].comap(r =>
      ujson.Obj(
        "category" -> r.category.jsonify,
        "version" -> r.version.jsonify,
        "type" -> r.`type`.jsonify,
        "filepath" -> r.filepath.jsonify,
        "executionTime" -> ujson.Num(r.executionTime)
      )
    )

  implicit def cleanResultWriter: upickle.Writer[CleanResult] =
    upickle.writer[ujson.Obj].comap(r =>
      ujson.Obj(
        "flywayVersion" -> r.flywayVersion.jsonify,
        "database" -> r.database.jsonify,
        "operation" -> r.operation.jsonify,
        "warnings" -> ujson.Arr.from(r.warnings.asScala.toSeq.map(_.jsonify)),
        "schemasCleaned" -> ujson.Arr.from(r.schemasCleaned.asScala.toSeq.map(_.jsonify)),
        "schemasDropped" -> ujson.Arr.from(r.schemasDropped.asScala.toSeq.map(_.jsonify))
      )
    )
  implicit def migrateResultWriter: upickle.Writer[MigrateResult] =
    upickle.writer[ujson.Obj].comap(r =>
      ujson.Obj(
        "flywayVersion" -> r.flywayVersion.jsonify,
        "database" -> r.database.jsonify,
        "operation" -> r.getOperation.jsonify,
        "warnings" -> r.warnings.asScala.toSeq.map(_.jsonify),
        "initialSchemaVersion" -> r.initialSchemaVersion.jsonify,
        "targetSchemaVersion" -> r.targetSchemaVersion.jsonify,
        "schemaName" -> r.schemaName.jsonify,
        "migrations" -> ujson.Arr.from(r.migrations.asScala.toSeq.map(upickle.write(_))),
        "migrationsExecuted" -> ujson.Num(r.migrationsExecuted)
      )
    )
  implicit def baselineResultWriter: upickle.Writer[BaselineResult] =
    upickle.writer[ujson.Obj].comap(r =>
      ujson.Obj(
        "flywayVersion" -> r.flywayVersion.jsonify,
        "database" -> r.database.jsonify,
        "operation" -> r.operation.jsonify,
        "warnings" -> ujson.Arr.from(r.warnings.asScala.toSeq.map(_.jsonify)),
        "successfullyBaselined" -> ujson.Bool(r.successfullyBaselined),
        "baselineVersion" -> r.baselineVersion.jsonify
      )
    )
}
