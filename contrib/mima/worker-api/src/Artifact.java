package mill.mima.worker.api;

public class Artifact {
    public String prettyDep;
    public java.io.File file;

    public Artifact(String prettyDep, java.io.File file) {
        this.prettyDep = prettyDep;
        this.file = file;
    }
}
