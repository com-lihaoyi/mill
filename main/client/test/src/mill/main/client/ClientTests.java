package mill.main.client;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

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
        assertEquals(example, s);
        assertEquals(i.available(), 0);
    }


}