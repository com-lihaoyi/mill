package mill.main.client.lock;

public interface Locked {

    void release() throws Exception;
}
