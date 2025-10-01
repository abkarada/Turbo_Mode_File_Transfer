import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class BufferPool {
    private final ConcurrentLinkedQueue<ByteBuffer> pool = new ConcurrentLinkedQueue<>();
    private final AtomicInteger created = new AtomicInteger(0);
    private final int bufferSize;
    private final int maxPoolSize;
    private final boolean direct;
    
    public BufferPool(int bufferSize, int maxPoolSize, boolean direct) {
        this.bufferSize = bufferSize;
        this.maxPoolSize = maxPoolSize;
        this.direct = direct;
    }
    
        public ByteBuffer acquire() {
        ByteBuffer buffer = pool.poll();
        if (buffer == null) {
            buffer = direct ? 
                ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.BIG_ENDIAN) :
                ByteBuffer.allocate(bufferSize).order(ByteOrder.BIG_ENDIAN);
            created.incrementAndGet();
        } else {
            buffer.clear();        
         }
        return buffer;
    }
    
       public void release(ByteBuffer buffer) {
        if (buffer == null) return;
        
        if (pool.size() < maxPoolSize) {
            buffer.clear();
            pool.offer(buffer);
        }
    }
    
        public int getPoolSize() { return pool.size(); }
    public int getTotalCreated() { return created.get(); }
    
        public void clear() {
        pool.clear();
    }
    
    public static final BufferPool SMALL_BUFFER_POOL = 
		new BufferPool(2048, 50, true);  // 2KB buffer'lar - paket için yeterli
		
	public static final BufferPool MEDIUM_BUFFER_POOL = 
		new BufferPool(8192, 20, true);  // 8KB buffer'lar
		
	public static final BufferPool LARGE_BUFFER_POOL = 
		new BufferPool(65536, 10, true); // 64KB buffer'lar
}