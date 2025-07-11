package mill.init.importer.sbt

import pprint.Util.literalize

import scala.util.Using

class SbtExtractor(
    extractorAssembly: os.Path,
    cmd: Seq[os.Shellable],
    cwd: os.Path,
    env: Map[String, String],
    stdout: os.ProcessOutput
):

  def extractProjects(out: os.Path = os.temp()): Seq[SbtProjectIR] =
    os.proc(
      cmd,
      s"set SettingKey[File](\"millInitExtractFile\") in Global := file(${literalize(out.toString)})",
      s"apply -cp ${literalize(extractorAssembly.toString)} mill.init.importer.sbt.ApplyExtract",
      "millInitExtract"
    ).call(cwd = cwd, env = env, stdout = stdout)
    upickle.default.read(out.toNIO)

object SbtExtractor:

  def apply(javaHome: Option[os.Path] = None): SbtExtractor =
    val jar = Using(getClass.getResourceAsStream(BuildInfo.extractorAssemblyResource))(
      os.temp(_, suffix = ".jar")
    ).get
    val env = javaHome.fold(null)(path => Map("JAVA_HOME" -> path.toString))
    new SbtExtractor(
      extractorAssembly = jar,
      cmd = Seq("sbt"),
      cwd = null,
      env = env,
      stdout = os.Pipe
    )

  /* TODO remove
    https://github.com/raquo/Airstream v17.2.0
    https://github.com/typelevel/fs2 v3.11.0
   */
  def main(args: Array[String]) =
    val url = args(0)
    val branch = args(1)
    val sandbox = os.temp.dir()
    os.proc("git", "clone", "--depth", "1", url, "--branch", branch, sandbox)
      .call(stderr = os.Pipe)
    os.dynamicPwd.withValue(sandbox):
      val projects = SbtExtractor().extractProjects()
      pprint.pprintln(projects, height = 4000)
