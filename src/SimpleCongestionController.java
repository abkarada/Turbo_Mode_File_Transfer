import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Basit ve etkili congestion control - karmaşık algoritmalar yok!
 */
public class SimpleCongestionController {
    
    // Basit rate limiting
    private volatile long packetIntervalNs = 10_000; // 10μs = 100k pps başlangıç
    private volatile long lastSendTime = 0;
    
    // Statistics
    private final AtomicLong totalPacketsSent = new AtomicLong(0);
    private volatile boolean isLocalNetwork = false;
    
    public SimpleCongestionController() {
    }
    
    /**
     * Basit rate pacing - kompleks window kontrolü yok
     */
    public void rateLimitSend() {
        if (!isLocalNetwork && packetIntervalNs > 0) {
            long now = System.nanoTime();
            long timeSinceLastSend = now - lastSendTime;
            
            if (timeSinceLastSend < packetIntervalNs) {
                long sleepTime = packetIntervalNs - timeSinceLastSend;
                if (sleepTime > 1000) { // Sadece 1μs'den fazlaysa bekle
                    LockSupport.parkNanos(sleepTime);
                }
            }
            lastSendTime = System.nanoTime();
        }
        // Local network için hiç bekleme yok!
    }
    
    /**
     * Packet gönderimi bildirimi
     */
    public void onPacketSent() {
        totalPacketsSent.incrementAndGet();
    }
    
    /**
     * Loss detection - basit adaptasyon
     */
    public void onPacketLoss(int lostPacketCount) {
        if (!isLocalNetwork) {
            // Sadece biraz yavaşlat
            packetIntervalNs = Math.min((long)(packetIntervalNs * 1.1), 50_000); // Max 50μs = 20k pps
            System.out.println("🔴 Packet loss detected, slowing down to " + getCurrentRate() + " pps");
        }
    }
    
    /**
     * Successful transmission - hızlandır
     */
    public void onSuccess() {
        if (!isLocalNetwork) {
            // Yavaş yavaş hızlandır
            packetIntervalNs = Math.max((long)(packetIntervalNs * 0.99), 1_000); // Min 1μs = 1M pps
        }
    }
    
    /**
     * Local network için optimize et
     */
    public void enableLocalNetworkMode() {
        isLocalNetwork = true;
        packetIntervalNs = 0; // Hiç beklememe - maksimum hız!
        System.out.println("⚡ LOCAL NETWORK MODE - Zero rate limiting!");
    }
    
    /**
     * Normal network için
     */
    public void enableNormalMode() {
        isLocalNetwork = false;
        packetIntervalNs = 5_000; // 5μs = 200k pps reasonable
        System.out.println("📡 NORMAL NETWORK MODE - Controlled rate");
    }
    
    /**
     * Current sending rate
     */
    public long getCurrentRate() {
        if (packetIntervalNs <= 0) return 1_000_000; // Theoretical max
        return 1_000_000_000L / packetIntervalNs;
    }
    
    /**
     * Simple stats
     */
    public String getStats() {
        return String.format("Rate: %d pps, Sent: %d, Mode: %s",
                getCurrentRate(),
                totalPacketsSent.get(),
                isLocalNetwork ? "Local" : "Normal");
    }
    
    /**
     * Reset
     */
    public void reset() {
        totalPacketsSent.set(0);
        lastSendTime = 0;
    }
}