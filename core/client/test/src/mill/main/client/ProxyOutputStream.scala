import java.io.{FilterOutputStream, IOException, OutputStream}
//import org.apache.commons.io.IOUtils

/** A Proxy stream which acts as expected, that is it passes the method calls on to the proxied stream and doesn't change which methods are being called. It is an alternative base class to FilterOutputStream to increase reusability. <p> See the protected methods for ways in which a subclass can easily decorate a stream with custom pre-, post- or error
  * processing functionality. </p>
  */
class ProxyOutputStream(out: OutputStream) extends FilterOutputStream(out) {

  /** Invoked by the write methods after the proxied call has returned successfully. The number of bytes written (1 for the {@@@@@@@link#write(int)} method, buffer length for {@@@@@@@link#write(byte[])} , etc.) is given as an argument. <p> Subclasses can override this method to add common post-processing functionality without having to override all the write
    * methods. The default implementation does nothing.
    *
    * @since 2.0
    * @param n
    *   number of bytes written
    * @throws IOException
    *   if the post-processing fails
    */
  protected def afterWrite(n: Int): Unit = {
    // noop
  }

  /** Invoked by the write methods before the call is proxied. The number of bytes to be written (1 for the {@@@@@@@link#write(int)} method, buffer length for {@@@@@@@link#write(byte[])} , etc.) is given as an argument. <p> Subclasses can override this method to add common pre-processing functionality without having to override all the write methods. The
    * default implementation does nothing.
    *
    * @since 2.0
    * @param n
    *   number of bytes to be written
    * @throws IOException
    *   if the pre-processing fails
    */
  protected def beforeWrite(n: Int): Unit = {
    // noop
  }

  /** Invokes the delegate's {@@@@@@@codeclose()} method.
    * @throws IOException
    *   if an I/O error occurs.
    */
  @throws[IOException]
  override def close(): Unit = {
    super.close()
    // IOUtils.close(out, handleIOException)
  }

  /** Invokes the delegate's {@@@@@@@codeflush()} method.
    * @throws IOException
    *   if an I/O error occurs.
    */
  @throws[IOException]
  override def flush(): Unit = {
    try {
      out.flush()
    } catch {
      case e: IOException => handleIOException(e)
    }
  }

  /** Handle any IOExceptions thrown. <p> This method provides a point to implement custom exception handling. The default behavior is to re-throw the exception.
    * @param e
    *   The IOException thrown
    * @throws IOException
    *   if an I/O error occurs.
    * @since 2.0
    */
  protected def handleIOException(e: IOException): Unit = {
    throw e
  }

  /** Invokes the delegate's {@@@@@@@codewrite(byte[])} method.
    * @param bts
    *   the bytes to write
    * @throws IOException
    *   if an I/O error occurs.
    */
  @throws[IOException]
  override def write(bts: Array[Byte]): Unit = {
    try {
      val len = bts.length
      beforeWrite(len)
      out.write(bts)
      afterWrite(len)
    } catch {
      case e: IOException => handleIOException(e)
    }
  }

  /** Invokes the delegate's {@@@@@@@codewrite(byte[])} method.
    * @param bts
    *   the bytes to write
    * @param st
    *   The start offset
    * @param end
    *   The number of bytes to write
    * @throws IOException
    *   if an I/O error occurs.
    */
  @throws[IOException]
  override def write(bts: Array[Byte], st: Int, end: Int): Unit = {
    try {
      beforeWrite(end)
      out.write(bts, st, end)
      afterWrite(end)
    } catch {
      case e: IOException => handleIOException(e)
    }
  }

  /** Invokes the delegate's {@@@@@@@codewrite(int)} method.
    * @param idx
    *   the byte to write
    * @throws IOException
    *   if an I/O error occurs.
    */
  @throws[IOException]
  override def write(idx: Int): Unit = {
    try {
      beforeWrite(1)
      out.write(idx)
      afterWrite(1)
    } catch {
      case e: IOException => handleIOException(e)
    }
  }
}
