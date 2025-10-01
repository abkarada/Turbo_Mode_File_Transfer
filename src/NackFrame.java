import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class NackFrame {
    public static final int SIZE = 20;

    private final ByteBuffer buf;

    public NackFrame() {
        this.buf = ByteBuffer.allocateDirect(SIZE).order(ByteOrder.BIG_ENDIAN);
    }

    public ByteBuffer buffer() { return buf; }

    public void fill(long fileId, int baseSeq, long mask64) {
        buf.clear();
        buf.putLong(0, fileId);
        buf.putInt (8, baseSeq);
        buf.putLong(12, mask64);
        buf.limit(SIZE);
        buf.position(0);
    }

    public void resetForRetry() { buf.position(0).limit(SIZE); }

    public static long  fileId(ByteBuffer b)  { return b.getLong(0); }
    public static int   baseSeq(ByteBuffer b) { return b.getInt(8); }
    public static long  mask64(ByteBuffer b)  { return b.getLong(12); }
}
