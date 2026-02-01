package mill.javalib.pgp.worker

import mill.javalib.api.PgpKeyMaterial

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardOpenOption}

object InitGpgKeysMain {
  def main(args: Array[String]): Unit = {
    val outputSecretPath = extractArg(args, "--output-secret")
    println("=== PGP Key Setup for Sonatype Central Publishing ===")
    println("")
    println("This will create a new PGP key pair for signing your artifacts.")
    println("You will be prompted to enter:")
    println("  - Your real name")
    println("  - Your email address")
    println("  - A passphrase to protect your key")
    println("")

    def prompt(label: String): String = {
      print(label)
      System.out.flush()
      scala.io.StdIn.readLine()
    }

    val name = prompt("Enter your name: ")
    val email = prompt("Enter your email: ")

    println("")
    println("Step 1: Generating PGP key pair...")
    val passphrase = {
      print("Enter passphrase (leave empty for no passphrase): ")
      System.out.flush()
      val console = System.console()
      if (console != null) {
        val fromConsole = console.readPassword()
        val fromConsoleValue = if (fromConsole == null) "" else new String(fromConsole)
        if (fromConsoleValue.isEmpty) scala.io.StdIn.readLine() else fromConsoleValue
      } else {
        scala.io.StdIn.readLine()
      }
    }
    if (passphrase == null || passphrase.isEmpty) {
      System.err.println("Warning: Empty passphrase provided")
    }
    val userId = s"$name <$email>"
    val generated: PgpKeyMaterial = new PgpSignerWorker().generateKeyPair(
      userId = userId,
      passphrase = Option(passphrase).filter(_.nonEmpty)
    )

    println("")
    println("PGP key generated successfully!")
    println("")

    val keyId = generated.keyIdHex
    println(s"Generated key ID: $keyId")
    println("")

    // Step 2: Upload public key to keyserver
    println("Step 2: Uploading public key to keyserver.ubuntu.com...")
    try {
      val uploadResult = requests.post(
        url = "https://keyserver.ubuntu.com/pks/add",
        data = Map("keytext" -> generated.publicKeyArmored)
      )
      if (!uploadResult.is2xx) {
        System.err.println(
          s"Warning: Failed to upload key to keyserver (status ${uploadResult.statusCode})."
        )
        System.err.println(
          "You may need to upload manually via https://keyserver.ubuntu.com/pks/add"
        )
      } else {
        println("Public key uploaded successfully!")
      }
    } catch {
      case e: Exception =>
        System.err.println(s"Warning: Failed to upload key to keyserver: ${e.getMessage}")
    }
    println("")

    // Step 3: Verify key was uploaded
    println("Step 3: Verifying key upload...")
    val deadline = System.currentTimeMillis() + 30000
    var verified = false
    var lastError: Option[String] = None
    while (!verified && System.currentTimeMillis() < deadline) {
      try {
        val verifyResult = requests.get(
          url = "https://keyserver.ubuntu.com/pks/lookup",
          params = Map("op" -> "get", "search" -> s"0x$keyId")
        )
        if (verifyResult.is2xx && verifyResult.text().contains("BEGIN PGP PUBLIC KEY BLOCK")) {
          verified = true
        } else {
          lastError = Some(
            s"Request to https://keyserver.ubuntu.com/pks/lookup failed with status code ${verifyResult.statusCode}"
          )
        }
      } catch {
        case e: Exception =>
          lastError = Some(e.getMessage)
      }
      if (!verified) Thread.sleep(2000)
    }
    if (verified) {
      println("Key verified on keyserver!")
    } else {
      System.err.println("Warning: Could not verify key on keyserver.")
      lastError.foreach(err => System.err.println(s"Warning: $err"))
      System.err.println(
        "This may be due to keyserver propagation delay. Try again later with:"
      )
      System.err.println(
        s"  https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x$keyId"
      )
    }
    println("")

    // Base64 encode the key (without newlines for environment variable)
    val secretKeyBase64 =
      java.util.Base64.getEncoder.encodeToString(generated.secretKeyArmored.getBytes("UTF-8"))

    outputSecretPath.foreach { pathString =>
      val secretPath = Paths.get(pathString)
      writeString(secretPath, generated.secretKeyArmored)
      println("")
      println(s"Saved secret key to: ${secretPath.toAbsolutePath}")
      println("To store it in your home directory for manual use, you can run:")
      println(s"  cp ${secretPath.toAbsolutePath} ~/.mill/pgp-private-key.asc")
      println("")
    }

    println("")
    println("=== Setup Complete! ===")
    println("")
    println("Add these environment variables to your CI configuration or shell:")
    println("")
    println("-" * 72)
    println(s"export MILL_PGP_SECRET_BASE64=$secretKeyBase64")
    println(s"export MILL_PGP_PASSPHRASE=$passphrase")
    println("-" * 72)
    println("")
    println("For GitHub Actions, add these as repository secrets:")
    println("env:")
    println(s"  MILL_PGP_SECRET_BASE64: $secretKeyBase64")
    println(s"  MILL_PGP_PASSPHRASE: $passphrase")
    println("")
    println(s"Your key ID is: $keyId")
    println("")
    println("See https://central.sonatype.org/publish/requirements/gpg/ for more details.")
  }

  private def extractArg(args: Array[String], flag: String): Option[String] = {
    val flagPrefix = s"$flag="
    args.zipWithIndex.collectFirst {
      case (arg, _) if arg.startsWith(flagPrefix) => arg.drop(flagPrefix.length)
      case (arg, idx) if arg == flag && idx + 1 < args.length => args(idx + 1)
    }.filter(_.nonEmpty)
  }

  private def writeString(path: Path, contents: String): Unit = {
    Option(path.getParent).foreach(parent => Files.createDirectories(parent))
    Files.write(
      path,
      contents.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING
    )
  }
}
