public class GC {
    public static void main(String[] args) throws Exception{
        final long liveSetByteSize = Integer.parseInt(args[0]) * 1000000L;
        final int benchMillis = Integer.parseInt(args[1]);
        final int benchCount = Integer.parseInt(args[2]);
        // 0-490 array entries per object, * 4-bytes per entry,
        // + 20 byte array header = average 1000 bytes per entry
        final int maxObjectSize = 490;
        final int averageObjectSize = (maxObjectSize / 2) * 4 + 20;

        final int liveSetSize = (int)(liveSetByteSize / averageObjectSize);

        long maxPauseTotal = 0;
        long throughputTotal = 0;

        for(int i = 0; i < benchCount + 1; i++) {
            int chunkSize = 256;
            Object[] liveSet = new Object[liveSetSize];
            for(int j = 0; j < liveSetSize; j++) liveSet[j] = new int[j % maxObjectSize];
            System.gc();
            long maxPause = 0;
            long startTime = System.currentTimeMillis();

            long loopCount = 0;
            java.util.Random random = new java.util.Random(1337);
            int liveSetIndex = 0;

            while (startTime + benchMillis > System.currentTimeMillis()) {
                if (loopCount % liveSetSize == 0) Thread.sleep(1);
                long loopStartTime = System.currentTimeMillis();
                liveSetIndex = random.nextInt(liveSetSize);
                liveSet[liveSetIndex] = new int[liveSetIndex % maxObjectSize];
                long loopTime = System.currentTimeMillis() - loopStartTime;
                if (loopTime > maxPause) maxPause = loopTime;
                loopCount++;
            }
            if (i != 0) {
                long benchEndTime = System.currentTimeMillis();
                long bytesPerLoop = maxObjectSize / 2 * 4 + 20;
                throughputTotal += (long) (1.0 * loopCount * bytesPerLoop / 1000000 / (benchEndTime - startTime) * averageObjectSize);
                maxPauseTotal += maxPause;
            }

            System.out.println(liveSet[random.nextInt(liveSet.length)]);
        }

        long maxPause = maxPauseTotal / benchCount;
        long throughput = throughputTotal / benchCount;

        System.out.println("longest-gc: " + maxPause + " ms, throughput: " + throughput + " mb/s");
    }
}
