import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Batch packet sender for high-throughput transmission
 * Sends multiple packets in a single system call burst
 */
public class BatchSender {
    
    private static final int DEFAULT_BATCH_SIZE = 32; // 32 paket batch
    private static final int MAX_BATCH_SIZE = 128;    // Maksimum batch boyutu
    
    private final DatagramChannel channel;
    private final int batchSize;
    private final AtomicInteger totalPacketsSent = new AtomicInteger(0);
    
    // Batch buffers
    private final ByteBuffer[] batchBuffers;
    private int currentBatchIndex = 0;
    
    public BatchSender(DatagramChannel channel) {
        this(channel, DEFAULT_BATCH_SIZE);
    }
    
    public BatchSender(DatagramChannel channel, int batchSize) {
        this.channel = channel;
        this.batchSize = Math.min(batchSize, MAX_BATCH_SIZE);
        this.batchBuffers = new ByteBuffer[this.batchSize];
        
        // Pre-allocate batch buffers
        for(int i = 0; i < this.batchSize; i++) {
            this.batchBuffers[i] = ByteBuffer.allocateDirect(1500); // Jumbo frame support
        }
        
        System.out.println("📦 BatchSender initialized with batch size: " + this.batchSize);
    }
    
    /**
     * Add packet to current batch
     */
    public boolean addToBatch(ByteBuffer packet) {
        if(currentBatchIndex >= batchSize) {
            return false; // Batch full
        }
        
        // Copy packet to batch buffer
        ByteBuffer batchBuffer = batchBuffers[currentBatchIndex];
        batchBuffer.clear();
        batchBuffer.put(packet.duplicate());
        batchBuffer.flip();
        
        currentBatchIndex++;
        return true;
    }
    
    /**
     * Send current batch
     */
    public int sendBatch() throws IOException {
        if(currentBatchIndex == 0) {
            return 0; // No packets to send
        }
        
        int sentPackets = 0;
        
        // Send all packets in batch with minimal delay
        for(int i = 0; i < currentBatchIndex; i++) {
            ByteBuffer buffer = batchBuffers[i];
            buffer.rewind();
            
            try {
                int written = channel.write(buffer);
                if(written > 0) {
                    sentPackets++;
                    totalPacketsSent.incrementAndGet();
                }
            } catch(IOException e) {
                System.err.println("Batch send error at packet " + i + ": " + e.getMessage());
                throw e;
            }
        }
        
        // Reset batch
        currentBatchIndex = 0;
        
        return sentPackets;
    }
    
    /**
     * Send packet immediately (bypass batching)
     */
    public boolean sendImmediate(ByteBuffer packet) throws IOException {
        int written = channel.write(packet);
        if(written > 0) {
            totalPacketsSent.incrementAndGet();
            return true;
        }
        return false;
    }
    
    /**
     * Force send any remaining packets in batch
     */
    public int flushBatch() throws IOException {
        return sendBatch();
    }
    
    /**
     * Check if batch is full
     */
    public boolean isBatchFull() {
        return currentBatchIndex >= batchSize;
    }
    
    /**
     * Get current batch utilization
     */
    public double getBatchUtilization() {
        return (double) currentBatchIndex / batchSize;
    }
    
    /**
     * Get total packets sent
     */
    public int getTotalPacketsSent() {
        return totalPacketsSent.get();
    }
    
    /**
     * Get batch size
     */
    public int getBatchSize() {
        return batchSize;
    }
    
    /**
     * Get statistics
     */
    public String getStats() {
        return String.format("Batch: %d/%d (%.1f%%), Total: %d packets",
                currentBatchIndex, batchSize, getBatchUtilization() * 100, getTotalPacketsSent());
    }
}