package mill.init.importer.sbt

import pprint.Util.literalize

import scala.util.Using

class SbtBuildExporter(
    exporterAssembly: os.Path,
    cmd: Seq[os.Shellable],
    cwd: os.Path,
    env: Map[String, String],
    stdout: os.ProcessOutput
):

  def exportBuild(out: os.Path = os.temp()): Seq[ExportedSbtProject] =
    os.proc(
      cmd,
      s"set SettingKey[File](\"millInitExportFile\") in Global := file(${literalize(out.toString)})",
      s"apply -cp ${literalize(exporterAssembly.toString)} mill.init.importer.sbt.ApplyExport",
      "millInitExport"
    ).call(cwd = cwd, env = env, stdout = stdout)
    upickle.default.read(out.toNIO)

object SbtBuildExporter:

  def apply(javaHome: Option[os.Path] = None): SbtBuildExporter = {
    val jar = Using(getClass.getResourceAsStream(BuildInfo.exporterAssemblyResource))(
      os.temp(_, suffix = ".jar")
    ).get
    val env = javaHome.fold(null)(path => Map("JAVA_HOME" -> path.toString))
    new SbtBuildExporter(
      exporterAssembly = jar,
      cmd = Seq("sbt"),
      cwd = null,
      env = env,
      stdout = os.Pipe
    )
  }
