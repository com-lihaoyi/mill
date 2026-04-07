package qux;

import com.google.common.base.Strings;
import java.io.InputStream;

public class Qux {
    public static String getLineCount() throws Exception {
        InputStream is = Qux.class.getClassLoader().getResourceAsStream("line-count.txt");
        return new String(is.readAllBytes()).trim();
    }

    public static void main(String[] args) throws Exception {
        String count = getLineCount();
        System.out.println("Line Count: " + count);
        System.out.println("Padding: [" + Strings.padStart(count, 5, '0') + "]");
    }
}
