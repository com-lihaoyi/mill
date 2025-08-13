package mill.constants;

import static org.junit.Assert.assertArrayEquals;

import java.io.*;
import org.apache.commons.io.output.TeeOutputStream;
import org.junit.Test;

public class ProxyStreamTests {
  /**
   * Ad-hoc fuzz tests to try and make sure the stuff we write into the
   * `ProxyStreams.Output` and read out of the `ProxyStreams.Pumper` ends up
   * being the same
   */
  @Test
  public void test() throws Exception {
    // Test writes of sizes around 1, around 127, around 255, and much larger. These
    // are likely sizes to have bugs since we write data in chunks of size 127
    int[] interestingLengths = {
      1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 20, 30, 40, 50, 100, 126, 127, 128, 129, 130, 253, 254, 255,
      256, 257, 1000, 2000, 4000, 8000
    };
    byte[] interestingBytes = {
      -1, -127, -126, -120, -100, -80, -60, -40, -20, -10, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 10,
      20, 40, 60, 80, 100, 120, 125, 126, 127
    };

    for (int n : interestingLengths) {

      System.out.println("ProxyStreamTests fuzzing length " + n);
      for (int r = 1; r < interestingBytes.length + 1; r += 1) {
        byte[] outData = new byte[n];
        byte[] errData = new byte[n];
        for (int j = 0; j < n; j++) {
          // fill test data blobs with arbitrary bytes from `interestingBytes`, negating
          // the bytes we use for `errData` so we can distinguish it from `outData`
          //
          // offset the start byte we use by `r`, so we exercise writing blobs
          // that start with every value listed in `interestingBytes`
          outData[j] = interestingBytes[(j + r) % interestingBytes.length];
          errData[j] = (byte) -interestingBytes[(j + r) % interestingBytes.length];
        }

        // Run all tests both with the format `ProxyStream.END` packet
        // being sent as well as when the stream is unceremoniously closed
        test0(outData, errData, r, false);
        test0(outData, errData, r, true);
      }
    }
  }

  public void test0(byte[] outData, byte[] errData, int repeats, boolean gracefulEnd)
      throws Exception {
    PipedOutputStream pipedOutputStream = new PipedOutputStream();
    PipedInputStream pipedInputStream = new PipedInputStream(1000000);

    pipedInputStream.connect(pipedOutputStream);

    var srcOut = new ProxyStream.Output(pipedOutputStream, ProxyStream.StreamType.OUT);
    var srcErr = new ProxyStream.Output(pipedOutputStream, ProxyStream.StreamType.ERR);

    // Capture both the destOut/destErr from the pumper, as well as the destCombined
    // to ensure the individual streams contain the right data and combined stream
    // is in the right order
    ByteArrayOutputStream destOut = new ByteArrayOutputStream();
    ByteArrayOutputStream destErr = new ByteArrayOutputStream();
    ByteArrayOutputStream destCombined = new ByteArrayOutputStream();
    ProxyStream.Pumper pumper = new ProxyStream.Pumper(
        pipedInputStream,
        new TeeOutputStream(destOut, destCombined),
        new TeeOutputStream(destErr, destCombined));

    new Thread(() -> {
          try {
            for (int i = 0; i < repeats; i++) {
              srcOut.write(outData);
              srcErr.write(errData);
            }

            if (gracefulEnd) ProxyStream.sendEnd(pipedOutputStream, (byte) 0);
            else {
              pipedOutputStream.close();
            }
          } catch (Exception e) {
            e.printStackTrace();
          }
        })
        .start();

    Thread pumperThread = new Thread(pumper);

    pumperThread.start();
    pumperThread.join();

    // Check that the individual `destOut` and `destErr` contain the correct bytes
    assertArrayEquals(repeatArray(outData, repeats), destOut.toByteArray());
    assertArrayEquals(repeatArray(errData, repeats), destErr.toByteArray());

    // Check that the combined `destCombined` contains the correct bytes in the correct order
    byte[] combinedData = new byte[outData.length + errData.length];

    System.arraycopy(outData, 0, combinedData, 0, outData.length);
    System.arraycopy(errData, 0, combinedData, outData.length, errData.length);

    assertArrayEquals(repeatArray(combinedData, repeats), destCombined.toByteArray());
  }

  private static byte[] repeatArray(byte[] original, int n) {
    byte[] result = new byte[original.length * n];

    for (int i = 0; i < n; i++) {
      System.arraycopy(original, 0, result, i * original.length, original.length);
    }

    return result;
  }
}
