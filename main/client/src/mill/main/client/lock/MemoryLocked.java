package mill.main.client.lock;

class MemoryLocked implements Locked {

    private java.util.concurrent.locks.Lock l;

    public MemoryLocked(java.util.concurrent.locks.Lock l) {
        this.l = l;
    }

    public void release() throws Exception {
        l.unlock();
    }
}
