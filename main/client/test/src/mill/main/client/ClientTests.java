package mill.main.client;

import static de.tobiasroeser.lambdatest.Expect.expectEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.*;

public class ClientTests {
    @Test
    public void readWriteInt() throws Exception{
        int[] examples = {
                0, 1, 126, 127, 128, 254, 255, 256, 1024, 99999, 1234567,
                Integer.MAX_VALUE, Integer.MAX_VALUE / 2, Integer.MIN_VALUE
        };
        for(int example0: examples){
            for(int example: new int[]{-example0, example0}){
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                Util.writeInt(o, example);
                ByteArrayInputStream i = new ByteArrayInputStream(o.toByteArray());
                int s = Util.readInt(i);
                assertEquals(example, s);
                assertEquals(i.available(), 0);
            }
        }
    }
    @Test
    public void readWriteString() throws Exception{
        String[] examples = {
                "",
                "hello",
                "i am cow",
                "i am cow\nhear me moo\ni weight twice as much as you",
                "我是一个叉烧包",
        };
        for(String example: examples){
            checkStringRoundTrip(example);
        }
    }

    @Test
    public void readWriteBigString() throws Exception{
        int[] lengths = {0, 1, 126, 127, 128, 254, 255, 256, 1024, 99999, 1234567};
        for(int i = 0; i < lengths.length; i++){
            final char[] bigChars = new char[lengths[i]];
            java.util.Arrays.fill(bigChars, 'X');
            checkStringRoundTrip(new String(bigChars));
        }
    }

    public void checkStringRoundTrip(String example) throws Exception{
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        Util.writeString(o, example);
        ByteArrayInputStream i = new ByteArrayInputStream(o.toByteArray());
        String s = Util.readString(i);
        expectEquals(example, s, "String as bytes: ["+example.getBytes()+"] differs from expected: ["+s.getBytes()+"]");
        expectEquals(i.available(), 0);
    }

    public byte[] readSamples(String ...samples) throws Exception{
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for(String sample: samples) {
            byte[] bytes = java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(getClass().getResource(sample).toURI())
            );
            out.write(bytes);
        }
        return out.toByteArray();
    }
    @Test
    public void tinyProxyInputOutputStream() throws Exception{
        proxyInputOutputStreams(
                Arrays.copyOf(readSamples("/bandung.jpg"), 30),
                readSamples(),
                10
        );
    }
    @Test
    public void leftProxyInputOutputStream() throws Exception{
        proxyInputOutputStreams(
                readSamples("/bandung.jpg", "/akanon.mid", "/gettysburg.txt", "/pip.tar.gz"),
                readSamples(),
                2950
        );
    }
    @Test
    public void rightProxyInputOutputStream() throws Exception{
        proxyInputOutputStreams(
                readSamples(),
                readSamples("/bandung.jpg", "/akanon.mid", "/gettysburg.txt", "/pip.tar.gz"),
                3000
        );
    }
    @Test
    public void mixedProxyInputOutputStream() throws Exception{
        proxyInputOutputStreams(
                readSamples("/bandung.jpg", "/gettysburg.txt"),
                readSamples("/akanon.mid", "/pip.tar.gz"),
                3050
        );
    }

    /**
     * Make sure that when we shove data through both ProxyOutputStreams in
     * variously sized chunks, we get the exact same bytes back out from the
     * ProxyStreamPumper.
     */
    public void proxyInputOutputStreams(byte[] samples1,
                                        byte[] samples2,
                                        int chunkMax) throws Exception{

        ByteArrayOutputStream pipe = new ByteArrayOutputStream();
        OutputStream src1 = new ProxyOutputStream(pipe, 1);
        OutputStream src2 = new ProxyOutputStream(pipe, -1);

        Random random = new Random(31337);

        int i1 = 0;
        int i2 = 0;
        while(i1 < samples1.length || i2 < samples2.length){
            int chunk = random.nextInt(chunkMax);
            if (random.nextBoolean() && i1 < samples1.length){
                src1.write(samples1, i1, Math.min(samples1.length-i1, chunk));
                src1.flush();
                i1 += chunk;
            }else if (i2 < samples2.length){
                src2.write(samples2, i2, Math.min(samples2.length-i2, chunk));
                src2.flush();
                i2 += chunk;
            }
        }

        byte[] bytes = pipe.toByteArray();


        ByteArrayOutputStream dest1 = new ByteArrayOutputStream();
        ByteArrayOutputStream dest2 = new ByteArrayOutputStream();
        ProxyStreamPumper pumper = new ProxyStreamPumper(
                new ByteArrayInputStream(bytes),
                dest1, dest2
        );
        pumper.run();
        assertTrue(Arrays.equals(samples1, dest1.toByteArray()));
        assertTrue(Arrays.equals(samples2, dest2.toByteArray()));
    }

}
