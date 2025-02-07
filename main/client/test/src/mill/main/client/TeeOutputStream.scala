package mill.main.client

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Ported through chatGPT to Scala.js from Apache Commons IO 2.11.0

import java.io.{IOException, OutputStream}

/**
 * Classic splitter of {@@@@@@@linkOutputStream} . Named after the Unix 'tee' command. It allows a stream to be branched off so there are now two streams.
 */
class TeeOutputStream(out: OutputStream, branch: OutputStream) extends ProxyOutputStream(out) {

  /**
   * The second OutputStream to write to.
   */
  protected var branchy: OutputStream = branch

  /**
   * Closes both output streams. <p> If closing the main output stream throws an exception, attempt to close the branch output stream. </p>
   *
   * <p> If closing the main and branch output streams both throw exceptions, which exceptions is thrown by this method is currently unspecified and subject to change. </p>
   *
   * @throws IOException
   *   if an I/O error occurs.
   */
  @throws[IOException]
  override def close(): Unit = {
    try {
      super.close()
    } finally {
      this.branch.close()
    }
  }

  /**
   * Flushes both streams.
   *
   * @throws IOException
   *   if an I/O error occurs.
   */
  @throws[IOException]
  override def flush(): Unit = {
    super.flush()
    this.branch.flush()
  }

  /**
   * Writes the bytes to both streams.
   *
   * @param b
   *   the bytes to write
   * @throws IOException
   *   if an I/O error occurs.
   */
  @throws[IOException]
  override def write(b: Array[Byte]): Unit = {
    super.write(b)
    this.branch.write(b)
  }

  /**
   * Writes the specified bytes to both streams.
   *
   * @param b
   *   the bytes to write
   * @param off
   *   The start offset
   * @param len
   *   The number of bytes to write
   * @throws IOException
   *   if an I/O error occurs.
   */
  @throws[IOException]
  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    super.write(b, off, len)
    this.branchy.write(b, off, len)
  }

  /**
   * Writes a byte to both streams.
   *
   * @param b
   *   the byte to write
   * @throws IOException
   *   if an I/O error occurs.
   */
  @throws[IOException]
  override def write(b: Int): Unit = {
    super.write(b)
    this.branchy.write(b)
  }
}
