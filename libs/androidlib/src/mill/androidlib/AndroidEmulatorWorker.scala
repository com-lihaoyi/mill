package mill.androidlib

import mill.api.{Logger, PathRef}

import scala.jdk.OptionConverters.RichOptional

class AndroidEmulatorWorker(adbPath: PathRef, emulatorId: String) extends AutoCloseable {

  private def waitForDevice(log: Logger): Unit = {
    val BootedIndicator = "1"

    def getBootflag: String = {
      val result = os.call(
        (
          adbPath.path,
          "-s",
          emulatorId,
          "shell",
          "getprop",
          "sys.boot_completed"
        ),
        check = false
      )
      if (result.exitCode != 0)
        "0"
      else
        result.out.trim()
    }

    var bootflag = getBootflag
    var triesLeft = 25

    while (bootflag != BootedIndicator && triesLeft > 0) {
      log.debug(s"Waiting for device to boot. Bootflag: $bootflag . Tries left ${triesLeft}")
      Thread.sleep(1000)
      triesLeft -= 1
      bootflag = getBootflag
    }

    if (bootflag != BootedIndicator)
      throw new Exception("Device failed to boot")
  }

  def startEmulator(args: Seq[String], log: Logger): String = {

    log.debug(s"Starting emulator with args: ${args.mkString(" ")}")

    val startEmuCmd = os.spawn(
      args
    )

    log.debug(s"Started emulator with stdout ${startEmuCmd.stdout}, stderr ${startEmuCmd.stderr}")

    val bootMessage: Option[String] = startEmuCmd.stdout.buffered.lines().filter(l => {
      log.debug(l.trim())
      l.contains("Boot completed in") || l.contains("Successfully loaded snapshot")
    }).findFirst().toScala

    if (bootMessage.isEmpty) {
      throw new Exception(s"Emulator failed to start: ${startEmuCmd.exitCode()}")
    }

    waitForDevice(log)

    bootMessage.get
  }

  override def close(): Unit = {
    os.call(
      (
        adbPath.path,
        "-s",
        emulatorId,
        "emu",
        "kill"
      )
    )
  }

}
