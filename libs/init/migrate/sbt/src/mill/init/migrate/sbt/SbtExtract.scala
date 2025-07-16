package mill.init.migrate
package sbt

import mill.constants.Util
import pprint.Util.literalize

import scala.util.Using

class SbtExtract(cmd: String, options: os.Shellable, jar: os.Path):

  def extractModules(
      testModuleName: String = "test",
      cwd: os.Path = null,
      stdout: os.ProcessOutput = os.Inherit
  ) =
    val out = os.temp()
    val args = s"(file(${literalize(out.toString())}), ${literalize(testModuleName)})"
    val script = Seq(
      s"set SettingKey[(File, String)](\"millInitExtractArgs\") in Global := $args",
      s"apply -cp ${literalize(jar.toString)} mill.init.migrate.sbt.ApplyExtract",
      "millInitExtract"
    )
    os.proc(cmd, options, script).call(cwd = cwd, stdout = stdout)
    upickle.default.read[Seq[MetaModule[SbtModuleMetadata]]](out.toNIO)

object SbtExtract:

  def apply(sbtCmd: Option[String] = None, sbtOptions: Seq[String] = Nil): SbtExtract =
    val cmd = sbtCmd.getOrElse(defaultSbtCmd)
    val jar = Using.resource(getClass.getResourceAsStream(BuildInfo.extractAssemblyResource))(
      os.temp(_, suffix = ".jar")
    )
    new SbtExtract(cmd = cmd, options = sbtOptions, jar = jar)

  def defaultSbtCmd =
    Option.when(os.exists(os.pwd / "sbt"))("./sbt")
      .orElse(Option.when(os.exists(os.pwd / "sbtx"))("./sbtx"))
      .getOrElse(if Util.isWindows then "sbt.bat" else "sbt")
