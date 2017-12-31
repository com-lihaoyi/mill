package better.files

import java.nio.charset.Charset
import java.nio.file.attribute.{PosixFileAttributes, PosixFilePermission, PosixFilePermissions}
import java.util.zip.Deflater

import scala.collection.JavaConverters._

/**
  * Do file ops using a UNIX command line DSL
  */
object Dsl {
  def ~ : File =
    File.home

  def pwd: File =
    File.currentWorkingDirectory

  def cwd: File =
    pwd

  val `..`: File => File =
    _.parent

  val  `.`: File => File =
    identity

  /**
    * Adds some symbolic operations to file
    * @param file
    */
  implicit class SymbolicOperations(val file: File) {
    /**
      * Allows navigation up e.g. file / .. / ..
      *
      * @param f
      * @return
      */
    def /(f: File => File): File =
      f(file)

    def <<(line: String)(implicit charset: Charset = defaultCharset): file.type =
      file.appendLines(line)(charset)

    def >>:(line: String)(implicit charset: Charset = defaultCharset): file.type =
      file.appendLines(line)(charset)

    def <(text: String)(implicit openOptions: File.OpenOptions = File.OpenOptions.default, charset: Charset = defaultCharset): file.type =
      file.write(text)(openOptions, charset)

    def `>:`(text: String)(implicit openOptions: File.OpenOptions = File.OpenOptions.default, charset: Charset = defaultCharset): file.type =
      file.write(text)(openOptions, charset)

    def `!`(implicit charset: Charset = defaultCharset): String =
      file.contentAsString(charset)

    def `===`(that: File): Boolean =
      file.isSameContentAs(that)

    def !==(that: File): Boolean =
      !(file === that)
  }

  def cp(from: File, to: File): File = {
    if (to.isDirectory) {
      from.copyToDirectory(to)
    } else {
      from.copyTo(to, overwrite = true)
    }
  }

  def mv(from: File, to: File): File = {
    if (to.isDirectory) {
      from.moveToDirectory(to)
    } else {
      from.moveTo(to, overwrite = true)
    }
  }

  def rm(file: File): File =
    file.delete(swallowIOExceptions = true)

  def del(file: File): File =
    rm(file)

  def ln(file1: File, file2: File): File =
    file1.linkTo(file2)

  def ln_s(file1: File, file2: File): File =
    file1.symbolicLinkTo(file2)

  def cat(files: File*): Seq[Iterator[Byte]] =
    files.map(_.bytes)

  def ls(file: File): Files =
    file.list

  def dir(file: File): Files =
    ls(file)

  def ls_r(file: File): Files =
    file.listRecursively

  def touch(file: File): File =
    file.touch()

  def mkdir(file: File): File =
    file.createDirectory()

  def md5(file: File): String =
    file.md5

  def sha1(file: File): String =
    file.sha1

  def sha256(file: File): String =
    file.sha256

  def sha512(file: File): String =
    file.sha512

  def mkdirs(file: File): File =
    file.createDirectories()

  def chown(owner: String, file: File): File =
    file.setOwner(owner)

  def chgrp(group: String, file: File): File =
    file.setGroup(group)

  /**
    * Update permission of this file
    *
    * @param permissions Must be 9 character POSIX permission representation e.g. "rwxr-x---"
    * @param file
    * @return file
    */
  def chmod(permissions: String, file: File): File =
    file.setPermissions(PosixFilePermissions.fromString(permissions).asScala.toSet)

  def chmod_+(permission: PosixFilePermission, file: File): File =
    file.addPermission(permission)

  def chmod_-(permission: PosixFilePermission, file: File): File =
    file.removePermission(permission)

  def stat(file: File): PosixFileAttributes =
    file.posixAttributes

  def unzip(zipFile: File)(destination: File)(implicit charset: Charset = defaultCharset): destination.type =
    zipFile.unzipTo(destination)(charset)

  def zip(files: File*)(destination: File, compressionLevel: Int = Deflater.DEFAULT_COMPRESSION)(implicit charset: Charset = defaultCharset): destination.type =
    destination.zipIn(files.iterator, compressionLevel)(charset)
}
