package mill.main.client.lock;

class FileLocked implements Locked {

    private java.nio.channels.FileLock lock;

    public FileLocked(java.nio.channels.FileLock lock) {
        this.lock = lock;
    }

    public void release() throws Exception {
        this.lock.release();
    }
}
