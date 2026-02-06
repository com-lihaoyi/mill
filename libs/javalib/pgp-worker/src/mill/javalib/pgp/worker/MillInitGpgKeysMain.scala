package mill.javalib.pgp.worker

import mill.javalib.api.PgpKeyMaterial
import mill.util.ShellConfiguration

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardOpenOption}

object MillInitGpgKeysMain {

  class UserAbortException extends RuntimeException("User aborted")

  def main(args: Array[String]): Unit = {
    val outputSecretPath = extractArg(args, "--output-secret")
    val detectedName = extractArg(args, "--name")
    val detectedEmail = extractArg(args, "--email")
    val detectedUrl = extractArg(args, "--url")

    try run(outputSecretPath, detectedName, detectedEmail, detectedUrl)
    catch { case _: UserAbortException => println("\nAborted.") }
  }

  private def run(
      outputSecretPath: Option[String],
      detectedName: Option[String],
      detectedEmail: Option[String],
      detectedUrl: Option[String]
  ): Unit = {
    println("=== PGP Key Setup for Sonatype Central Publishing ===")
    println("")
    println("This will create a new PGP key pair for signing your artifacts.")
    println("You will be prompted to enter:")
    println("  - Your real name")
    println("  - Your email address")
    println("  - A passphrase to protect your key")
    println("(Type \"q\" at any prompt to abort)")
    println("")
    println("Step 1: Generating PGP key pair...")
    def prompt(label: String, hint: Option[String] = None): String = {
      val promptText = hint match {
        case Some(h) => s"$label[$h]: "
        case None => label
      }
      print(promptText)
      System.out.flush()
      val input = readLineOrAbort()
      if (input.trim.isEmpty) hint.getOrElse("") else input.trim
    }

    val name = prompt("Enter your name: ", detectedName)
    val email = prompt("Enter your email: ", detectedEmail)

    print("Enter passphrase (leave empty for no passphrase): ")
    System.out.flush()
    val passphrase = readPasswordOrEmpty()

    if (passphrase.isEmpty) {
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

    println("Step 2: Uploading public key to keyserver.ubuntu.com...")
    uploadAndVerifyKey(generated, keyId)
    println("")

    val secretKeyBase64 =
      java.util.Base64.getEncoder.encodeToString(generated.secretKeyArmored.getBytes("UTF-8"))

    outputSecretPath.foreach { pathString =>
      val secretPath = Paths.get(pathString)
      writeString(secretPath, generated.secretKeyArmored)
      println(s"The generated PGP secret key has been saved to:")
      println(s"  ${secretPath.toAbsolutePath}")
      println("")
    }

    println("")
    println("=== Setup Complete! ===")
    println("")

    printLocalShellInstructions(secretKeyBase64)

    val sonatypeCredentials = promptSonatypeCredentials()

    offerShellConfigSetup(secretKeyBase64, passphrase, sonatypeCredentials)

    val remoteUrl = detectRemoteUrl(detectedUrl)
    val repoSlug = remoteUrl.flatMap(parseGitHubSlug)
    val isGitHub = remoteUrl.forall(url =>
      url.contains("github.com") || parseGitHubSlug(url).isDefined
    )

    if (isGitHub) {
      printGitHubActionsInstructions()
      offerGitHubSecretUpload(repoSlug, secretKeyBase64, passphrase, sonatypeCredentials)
      printWorkflowYaml(passphrase)
    }
  }

  private def readLineOrEmpty(): String = {
    val line = scala.io.StdIn.readLine()
    if (line == null) "" else line
  }

  private def readLineOrAbort(): String = {
    val line = readLineOrEmpty()
    if (line.trim.toLowerCase == "q" || line.trim.toLowerCase == "quit")
      throw new UserAbortException
    line
  }

  private def readPasswordOrEmpty(): String = {
    System.console() match {
      case null => readLineOrEmpty()
      case console =>
        val chars = console.readPassword()
        if (chars == null) "" else new String(chars)
    }
  }

  private def promptSonatypeCredentials(): Option[(String, String)] = {
    print(
      "Enter Sonatype username (from https://central.sonatype.com/usertoken, or press Enter to skip): "
    )
    System.out.flush()
    readLineOrAbort().trim match {
      case "" => None
      case username =>
        print("Enter Sonatype password: ")
        System.out.flush()
        val password = readPasswordOrEmpty()
        if (password.isEmpty) None
        else Some((username, password))
    }
  }

  private val MaxKeyserverRetries = 3

  private def uploadAndVerifyKey(generated: PgpKeyMaterial, keyId: String): Unit = {
    try {
      val uploadResult = requests.post(
        url = "https://keyserver.ubuntu.com/pks/add",
        data = Map("keytext" -> generated.publicKeyArmored)
      )

      if (uploadResult.is2xx) println("Public key uploaded successfully!")
      else {
        System.err.println(
          s"Warning: Failed to upload key to keyserver (status ${uploadResult.statusCode}).\n" +
            "You may need to upload manually via https://keyserver.ubuntu.com/pks/add"
        )
      }
    } catch {
      case e: java.io.IOException =>
        System.err.println(s"Warning: Failed to upload key to keyserver: ${e.getMessage}")
      case e: requests.RequestFailedException =>
        System.err.println(s"Warning: Failed to upload key to keyserver: ${e.getMessage}")
    }
    println("")

    println("Step 3: Verifying key upload...")
    var verified = false
    var lastError: Option[String] = None
    var attempt = 0
    while (!verified && attempt < MaxKeyserverRetries) {
      attempt += 1
      try {
        val verifyResult = requests.get(
          url = "https://keyserver.ubuntu.com/pks/lookup",
          params = Map("op" -> "get", "search" -> s"0x$keyId")
        )

        if (verifyResult.is2xx && verifyResult.text().contains("BEGIN PGP PUBLIC KEY BLOCK"))
          verified = true
        else {
          lastError = Some(
            s"Request to https://keyserver.ubuntu.com/pks/lookup failed with status code ${verifyResult.statusCode}"
          )
        }
      } catch {
        case e: java.io.IOException => lastError = Some(e.getMessage)
        case e: requests.RequestFailedException => lastError = Some(e.getMessage)
      }

      if (!verified && attempt < MaxKeyserverRetries) Thread.sleep(2000)
    }

    if (verified) println("Key verified on keyserver!")
    else {
      System.err.println("Warning: Could not verify key on keyserver.")
      lastError.foreach(err => System.err.println(s"Warning: $err"))
      System.err.println(
        "This may be due to keyserver propagation delay. Try again later with:"
      )
      System.err.println(
        s"  https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x$keyId"
      )
    }
  }

  private def printLocalShellInstructions(secretKeyBase64: String): Unit = {
    println("--- Local Shell Configuration ---")
    println("To publish from your shell, set these environment variables:")
    println("  export MILL_PGP_SECRET_BASE64=<base64 encoded key>")
    println("  export MILL_PGP_PASSPHRASE=<passphrase>")
    println("  export MILL_SONATYPE_USERNAME=<from https://central.sonatype.com/usertoken>")
    println("  export MILL_SONATYPE_PASSWORD=<from https://central.sonatype.com/usertoken>")
    println("")
    println(
      s"Note: MILL_PGP_SECRET_BASE64 is ${secretKeyBase64.length} characters when base64 encoded."
    )
    println("Some shells may have issues with very long values in config files.")
    println("")
  }

  private def offerShellConfigSetup(
      secretKeyBase64: String,
      passphrase: String,
      sonatypeCredentials: Option[(String, String)]
  ): Unit = {
    val shells = ShellConfiguration.installedWithConfig
    if (shells.isEmpty) return

    val envVars: Seq[(String, String, String)] = Seq(
      ("MILL_PGP_SECRET_BASE64", secretKeyBase64, "<generated key>"),
      ("MILL_PGP_PASSPHRASE", passphrase, "<passphrase>")
    ) ++ sonatypeCredentials.map { case (u, _) =>
      ("MILL_SONATYPE_USERNAME", u, "<sonatype username>")
    }.toSeq ++ sonatypeCredentials.map { case (_, p) =>
      ("MILL_SONATYPE_PASSWORD", p, "<sonatype password>")
    }.toSeq

    println("")
    println("The following shell configurations were detected:")
    println("")
    shells.zipWithIndex.foreach { case ((shell, path), idx) =>
      println(s"  [${idx + 1}] $path")
      envVars.foreach { case (envName, _, placeholder) =>
        println(s"      ${shell.formatEnvVar(envName, placeholder)}")
      }
      println("")
    }

    print("Enter numbers to update (e.g. \"1 2\"), or press Enter to skip: ")
    System.out.flush()
    val input = readLineOrAbort()
    if (input.trim.nonEmpty) {
      val indices = input.trim.split("\\s+").flatMap(s => scala.util.Try(s.toInt - 1).toOption)
      val envPairs = envVars.map { case (envName, value, _) => (envName, value) }
      for (idx <- indices if idx >= 0 && idx < shells.length) {
        val (shell, path) = shells(idx)
        val diff = shell.previewUpsert(path, envPairs)
        if (diff.nonEmpty) {
          println(s"Changes to $path:")
          println(diff)
        }
      }
      print("Apply these changes? [y/N]: ")
      System.out.flush()
      val confirm = readLineOrAbort()
      if (confirm.trim.toLowerCase.startsWith("y")) {
        for (idx <- indices if idx >= 0 && idx < shells.length) {
          val (shell, path) = shells(idx)
          envVars.foreach { case (envName, value, _) =>
            shell.upsertEnvVar(path, envName, value)
          }
          println(s"  Updated $path")
        }
      }
      println("")
    }
  }

  private def detectRemoteUrl(detectedRepoUrl: Option[String]): Option[String] = {
    detectRemoteUrlFromGit().orElse(detectedRepoUrl)
  }

  private def detectRemoteUrlFromGit(): Option[String] = {
    try {
      val result = os.proc("git", "remote", "get-url", "origin")
        .call(mergeErrIntoOut = true, check = false)
      if (result.exitCode == 0) Some(result.out.text().trim).filter(_.nonEmpty)
      else None
    } catch {
      case _: java.io.IOException => None
    }
  }

  private def parseGitHubSlug(url: String): Option[String] = {
    val httpsPattern = """https?://github\.com/([^/]+/[^/]+?)(?:\.git)?/?$""".r
    val sshPattern = """git@github\.com:([^/]+/[^/]+?)(?:\.git)?$""".r
    url match {
      case httpsPattern(slug) => Some(slug)
      case sshPattern(slug) => Some(slug)
      case _ => None
    }
  }

  private def printGitHubActionsInstructions(): Unit = {
    println("--- GitHub Actions ---")
    println("To publish from GitHub Actions, add these repository secrets:")
    println("  MILL_PGP_SECRET_BASE64 = <base64 key>")
    println("  MILL_PGP_PASSPHRASE = <passphrase>")
    println("  MILL_SONATYPE_USERNAME = <from https://central.sonatype.com/usertoken>")
    println("  MILL_SONATYPE_PASSWORD = <from https://central.sonatype.com/usertoken>")
    println("")
  }

  private def offerGitHubSecretUpload(
      repoSlug: Option[String],
      secretKeyBase64: String,
      passphrase: String,
      sonatypeCredentials: Option[(String, String)]
  ): Unit = {
    if (!ShellConfiguration.findOnPath("gh")) return

    val ghAuthenticated =
      try {
        os.proc("gh", "auth", "status").call(mergeErrIntoOut = true, check = false).exitCode == 0
      } catch {
        case _: java.io.IOException => false
      }
    if (!ghAuthenticated) return

    val repo = repoSlug match {
      case Some(slug) => slug
      case None =>
        print("Enter GitHub repo (org/repo): ")
        System.out.flush()
        val input = readLineOrAbort().trim
        if (input.isEmpty) return
        input
    }

    println(s"The following secrets will be uploaded to $repo:")
    println("  MILL_PGP_SECRET_BASE64 = <generated key>")
    if (passphrase.nonEmpty) println("  MILL_PGP_PASSPHRASE = <passphrase>")
    sonatypeCredentials.foreach { _ =>
      println("  MILL_SONATYPE_USERNAME = <provided>")
      println("  MILL_SONATYPE_PASSWORD = <provided>")
    }
    if (sonatypeCredentials.isEmpty) {
      println("  MILL_SONATYPE_USERNAME = <not provided, skipping>")
      println("  MILL_SONATYPE_PASSWORD = <not provided, skipping>")
    }
    print(s"Upload these secrets to GitHub ($repo) now? [y/N]: ")
    System.out.flush()
    val confirm = readLineOrAbort()
    if (!confirm.trim.toLowerCase.startsWith("y")) return

    ghSecretSet(repo, "MILL_PGP_SECRET_BASE64", secretKeyBase64)
    if (passphrase.nonEmpty) ghSecretSet(repo, "MILL_PGP_PASSPHRASE", passphrase)

    sonatypeCredentials.foreach { case (username, password) =>
      ghSecretSet(repo, "MILL_SONATYPE_USERNAME", username)
      ghSecretSet(repo, "MILL_SONATYPE_PASSWORD", password)
    }
    println("")
  }

  private def ghSecretSet(repo: String, name: String, value: String): Unit = {
    try {
      val result = os.proc("gh", "secret", "set", name, "-R", repo)
        .call(stdin = value, mergeErrIntoOut = true, check = false)
      if (result.exitCode == 0) println(s"  Uploaded $name")
      else System.err.println(s"  Warning: Failed to upload $name: ${result.out.text().trim}")
    } catch {
      case e: java.io.IOException =>
        System.err.println(s"  Warning: Failed to upload $name: ${e.getMessage}")
    }
  }

  private def printWorkflowYaml(passphrase: String): Unit = {
    println("Then include them in .github/workflows/publish-artifacts.yml:")
    println("-" * 72)
    println("env:")
    println("  MILL_PGP_SECRET_BASE64: ${{ secrets.MILL_PGP_SECRET_BASE64 }}")
    if (passphrase.nonEmpty) {
      println("  MILL_PGP_PASSPHRASE: ${{ secrets.MILL_PGP_PASSPHRASE }}")
    }
    println("  MILL_SONATYPE_USERNAME: ${{ secrets.MILL_SONATYPE_USERNAME }}")
    println("  MILL_SONATYPE_PASSWORD: ${{ secrets.MILL_SONATYPE_PASSWORD }}")
    println("-" * 72)
  }

  private def extractArg(args: Array[String], flag: String): Option[String] = {
    val flagPrefix = s"$flag="
    args.zipWithIndex
      .collectFirst {
        case (arg, _) if arg.startsWith(flagPrefix) => arg.drop(flagPrefix.length)
        case (arg, idx) if arg == flag && idx + 1 < args.length => args(idx + 1)
      }
      .filter(_.nonEmpty)
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
