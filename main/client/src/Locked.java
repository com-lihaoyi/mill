package mill.main.client;

import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.concurrent.locks.ReentrantLock;


public interface Locked{
    public void release() throws Exception;
}