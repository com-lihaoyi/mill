package mill.contrib.scoverage.api;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;

public interface ScoverageReportWorkerApi2 {

  interface Logger {
    void error(String msg);

    void warn(String msg);

    void info(String msg);

    void debug(String msg);
  }

  interface Ctx {
    Logger log();

    Path dest();
  }

  public abstract static class ReportType implements Serializable {
    private String name;

    /*private[api]*/
    ReportType(String name) {}

    public static final ReportType Console = new ConsoleModule();
    public static final FileReportType Html = new HtmlModule();
    public static final FileReportType Xml = new XmlModule();
    public static final FileReportType XmlCobertura = new XmlCoberturaModule();

    /* private[api]*/
    static final class ConsoleModule extends ReportType implements Serializable {
      /* private[api]*/
      ConsoleModule() {
        super("Console");
      }
    }
    ;

    /* private[api]*/
    static final class HtmlModule extends FileReportType implements Serializable {
      /* private[api]*/
      HtmlModule() {
        super("Html", "htmlReport");
      }
    }
    ;

    /* private[api]*/
    static final class XmlModule extends FileReportType implements Serializable {
      /* private[api]*/
      XmlModule() {
        super("Xml", "xmlReport");
      }
    }

    /* private[api]*/
    static final class XmlCoberturaModule extends FileReportType implements Serializable {
      /* private[api]*/
      XmlCoberturaModule() {
        super("XmlCobertura", "xmlCoberturaReport");
      }
    }

    @Override
    public String toString() {
      return name;
    }
  }

  public abstract static class FileReportType extends ReportType implements Serializable {
    private final String folderName;

    /*private[api]*/
    FileReportType(String name, String folderName) {
      super(name);
      this.folderName = folderName;
    }

    public String folderName() {
      return folderName;
    }
  }

  void report(ReportType reportType, Path[] sources, Path[] dataDirs, Path sourceRoot, Ctx ctx);

  void validateCoverageMinimums(
      Path[] dataDirs,
      Path sourceRoot,
      Double statementCoverageMin,
      Double branchCoverageMin,
      Ctx ctx);

  static void makeAllDirs(Path path) throws IOException {
    // Replicate behavior of `os.makeDir.all(path)`
    if (Files.isDirectory(path) && Files.isSymbolicLink(path)) {
      // do nothing
    } else {
      Files.createDirectories(path);
    }
  }
}
