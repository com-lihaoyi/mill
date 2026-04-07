package qux;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.junit.jupiter.api.Test;

public class QuxTest {
    @Test
    public void testLineCount() throws Exception {
        assertThat(Qux.getLineCount(), equalTo("17"));
    }
}
