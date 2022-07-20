package mill.main.client.lock;

import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

class FileLock extends Lock {

    private RandomAccessFile raf;
    private FileChannel chan;

    public FileLock(String path) throws Exception {
        raf = new RandomAccessFile(path, "rw");
        chan = raf.getChannel();
    }

    public Locked lock() throws Exception {
        return new FileLocked(chan.lock());
    }

    public Locked tryLock() throws Exception {
        java.nio.channels.FileLock l = chan.tryLock();
        if (l == null) return null;
        else return new FileLocked(l);
    }

    public boolean probe() throws Exception {
        java.nio.channels.FileLock l = chan.tryLock();
        if (l == null) return false;
        else {
            l.release();
            return true;
        }
    }

    @Override
    public void close() throws Exception {
        chan.close();
        raf.close();
    }
}
