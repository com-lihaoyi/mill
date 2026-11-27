package formatter;

import com.diffplug.spotless.FormatterStep;
import com.github.vertical_blank.sqlformatter.SqlFormatter;

import java.io.File;

public class SQLFormatter implements FormatterStep {

    @Override
    public String getName() {
        return "custom-sql-format";
    }

    @Override
    public String format(String raw, File file) throws Exception {
        return SqlFormatter.format(raw);
    }

    @Override
    public void close() {
        // nothing to clean up
    }
}
