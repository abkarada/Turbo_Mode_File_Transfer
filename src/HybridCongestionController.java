import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * QUIC-inspired hybrid congestion control for NAK-based protocol
 * Combines QUIC's cubic congestion control with rate-based pacing
 */
public class HybridCongestionController {
    
    // QUIC-inspired congestion window (bytes)
    private volatile long congestionWindow = 32 * 1450; // 32 packets başlangıç
    private volatile long slowStartThreshold = Long.MAX_VALUE;
    private volatile long maxCongestionWindow = 256 * 1450; // 256 packets max
    
    // Bandwidth estimation (QUIC DeliveryRateEstimator benzeri)
    private volatile long estimatedBandwidthBps = 10_000_000; // 10 Mbps başlangıç
    private volatile long maxBandwidthBps = 100_000_000; // 100 Mbps max estimate
    
    // Pacing rate (bytes per second)
    private volatile long pacingRate = estimatedBandwidthBps;
    private volatile long packetIntervalNs = 0;
    
    // RTT tracking (QUIC RttStats benzeri)
    private volatile long smoothedRtt = 100_000_000; // 100ms başlangıç
    private volatile long rttVar = 50_000_000; // 50ms variance
    private volatile long minRtt = Long.MAX_VALUE;
    
    // Congestion state
    private enum CongestionState {
        SLOW_START,
        CONGESTION_AVOIDANCE,
        RECOVERY
    }
    private volatile CongestionState state = CongestionState.SLOW_START;
    
    // In-flight tracking
    private final AtomicLong bytesInFlight = new AtomicLong(0);
    private final AtomicLong packetsInFlight = new AtomicLong(0);
    
    // Statistics
    private final AtomicLong totalPacketsSent = new AtomicLong(0);
    private final AtomicLong totalBytesSent = new AtomicLong(0);
    private final AtomicLong totalLossCount = new AtomicLong(0);
    private volatile long lastStatsTime = System.nanoTime();
    private volatile long startTime = System.nanoTime();
    
    // Timing
    private volatile long lastSendTime = 0;
    private volatile long lastNackTime = 0;
    
    // Network type
    private volatile boolean isLocalNetwork = false;
    
    // Constants
    private static final int PACKET_SIZE = 1450;
    private static final double CUBIC_C = 0.4; // QUIC CUBIC constant
    private static final long MIN_PACING_INTERVAL = 250; // 250ns minimum (optimized for WAN)
    
    public HybridCongestionController() {
        updatePacingRate();
    }
    
    /**
     * QUIC-style pacing with bandwidth awareness
     */
    public void rateLimitSend() {
        // Local networks için pacing yok
        if (isLocalNetwork) {
            return;
        }
        
        // Congestion window kontrolü
        if (bytesInFlight.get() >= congestionWindow) {
            // Window dolu - biraz bekle ve tekrar kontrol et
            long waitTime = packetIntervalNs * 2;
            if (waitTime > 100_000) { // Max 100μs bekle
                LockSupport.parkNanos(waitTime);
            }
            return;
        }
        
        // Pacing kontrolü
        if (packetIntervalNs > MIN_PACING_INTERVAL) {
            long now = System.nanoTime();
            long timeSinceLastSend = now - lastSendTime;
            
            if (timeSinceLastSend < packetIntervalNs) {
                long sleepTime = packetIntervalNs - timeSinceLastSend;
                LockSupport.parkNanos(sleepTime);
            }
            lastSendTime = System.nanoTime();
        }
    }
    
    /**
     * Packet sent notification - QUIC OnPacketSent benzeri
     */
    public void onPacketSent() {
        onPacketSent(PACKET_SIZE);
    }
    
    public void onPacketSent(int packetSize) {
        bytesInFlight.addAndGet(packetSize);
        packetsInFlight.incrementAndGet();
        totalPacketsSent.incrementAndGet();
        totalBytesSent.addAndGet(packetSize);
    }
    
	/**
	 * Packet acknowledged (via absence in NACK) - QUIC OnPacketAcked benzeri
	 */
	public void onPacketAcked(int ackedBytes) {
		// Sadece gerçekten in-flight olan bytes'ları düş
		long currentInFlight = bytesInFlight.get();
		if (currentInFlight >= ackedBytes) {
			bytesInFlight.addAndGet(-ackedBytes);
			packetsInFlight.decrementAndGet();
		}        long now = System.nanoTime();
        
        // Bandwidth estimation güncelle
        updateBandwidthEstimate(ackedBytes, now);
        
        // Congestion window büyüt (QUIC CUBIC benzeri)
        if (state == CongestionState.SLOW_START) {
            // Exponential growth
            congestionWindow += ackedBytes;
            if (congestionWindow >= slowStartThreshold) {
                state = CongestionState.CONGESTION_AVOIDANCE;
                System.out.println("🔄 Switched to CONGESTION_AVOIDANCE");
            }
        } else if (state == CongestionState.CONGESTION_AVOIDANCE) {
            // CUBIC increase (simplified)
            long increase = (ackedBytes * ackedBytes) / congestionWindow;
            congestionWindow += Math.max(1, increase);
        }
        
        congestionWindow = Math.min(congestionWindow, maxCongestionWindow);
        updatePacingRate();
    }
    
    /**
     * NACK-based loss detection - QUIC OnPacketLost benzeri
     */
    public void onPacketLoss(int lostPacketCount) {
        onPacketLoss(lostPacketCount, lostPacketCount * PACKET_SIZE);
    }
    
	public void onPacketLoss(int lostPacketCount, int lostBytes) {
		if (lostPacketCount <= 0) return;
		
		totalLossCount.addAndGet(lostPacketCount);
		// In-flight düzeltmesi - negatif olmasın
		long currentInFlight = bytesInFlight.get();
		long currentPackets = packetsInFlight.get();
		
		bytesInFlight.set(Math.max(0, currentInFlight - lostBytes));
		packetsInFlight.set(Math.max(0, currentPackets - lostPacketCount));        lastNackTime = System.nanoTime();
        
        // QUIC-style congestion response - gentler for WAN
        if (state != CongestionState.RECOVERY) {
            state = CongestionState.RECOVERY;
            
            // Gentler multiplicative decrease for WAN
            if (isLocalNetwork) {
                // Local network - aggressive backoff
                slowStartThreshold = congestionWindow / 2;
                congestionWindow = Math.max(slowStartThreshold, 4 * PACKET_SIZE);
                estimatedBandwidthBps = (long)(estimatedBandwidthBps * 0.5); // 50% reduction
            } else {
                // WAN - gentler backoff for better recovery
                slowStartThreshold = (congestionWindow * 3) / 4;  // 75% threshold (was 50%)
                congestionWindow = Math.max(slowStartThreshold, 8 * PACKET_SIZE); // Min 8 packets (was 4)
                estimatedBandwidthBps = (long)(estimatedBandwidthBps * 0.8); // 20% reduction (was 50%)
            }
            
            updatePacingRate();
            
            System.out.printf("🔴 LOSS: %d packets, cwnd: %d -> %d bytes, bw: %.1f Mbps%n",
                lostPacketCount, 
                slowStartThreshold * 2, 
                congestionWindow,
                estimatedBandwidthBps / 1_000_000.0);
        }
    }
    
    /**
     * RTT measurement update - QUIC RttStats.UpdateRtt benzeri
     */
    public void updateRtt(long rttNs) {
        if (rttNs <= 0) return;
        
        // Min RTT güncelle
        if (rttNs < minRtt) {
            minRtt = rttNs;
        }
        
        // Smoothed RTT (EWMA)
        if (smoothedRtt == 0) {
            smoothedRtt = rttNs;
            rttVar = rttNs / 2;
        } else {
            long rttDelta = Math.abs(smoothedRtt - rttNs);
            rttVar = (3 * rttVar + rttDelta) / 4;
            smoothedRtt = (7 * smoothedRtt + rttNs) / 8;
        }
        
        // Recovery state'den çık eğer RTT iyileşmişse
        if (state == CongestionState.RECOVERY && 
            System.nanoTime() - lastNackTime > smoothedRtt * 2) {
            state = CongestionState.CONGESTION_AVOIDANCE;
            System.out.println("🟢 Exited RECOVERY state");
        }
    }
    
    /**
     * Bandwidth estimation update
     */
    private void updateBandwidthEstimate(int ackedBytes, long now) {
        long elapsed = now - lastStatsTime;
        if (elapsed > 100_000_000) { // 100ms'de bir güncelle
            long currentRate = (ackedBytes * 1_000_000_000L) / elapsed;
            
            // EWMA ile bandwidth estimate
            estimatedBandwidthBps = (long)(0.7 * estimatedBandwidthBps + 0.3 * currentRate);
            estimatedBandwidthBps = Math.min(estimatedBandwidthBps, maxBandwidthBps);
            
            lastStatsTime = now;
        }
    }
    
    /**
     * Pacing rate calculation - QUIC PacingSender benzeri
     */
    private void updatePacingRate() {
        if (isLocalNetwork) {
            packetIntervalNs = 0;
            return;
        }
        
        // Optimized WAN pacing - faster but stable
        // Bandwidth-delay product aware pacing
        long bdp = (estimatedBandwidthBps * smoothedRtt) / 1_000_000_000L;
        long targetWindow = Math.max(congestionWindow, bdp);
        
        // Pacing rate = (window / RTT) * gain - higher gain for WAN
        double pacingGain = (state == CongestionState.RECOVERY) ? 1.0 : 1.5; // Dynamic gain
        pacingRate = (long)((targetWindow * 1_000_000_000L * pacingGain) / smoothedRtt);
        pacingRate = Math.min(pacingRate, estimatedBandwidthBps * 3); // Max 3x bandwidth (was 2x)
        
        // Faster packet intervals for WAN
        if (pacingRate > 0) {
            packetIntervalNs = (PACKET_SIZE * 1_000_000_000L) / pacingRate;
            packetIntervalNs = Math.max(packetIntervalNs, 250); // 250ns minimum (was 500ns)
        } else {
            packetIntervalNs = 1000; // 1μs default for WAN
        }
    }
    
    /**
     * Network mode configuration
     */
    public void enableLocalNetworkMode() {
        isLocalNetwork = true;
        // BALANCED LOCAL MODE - Not too aggressive to avoid packet loss
        maxCongestionWindow = 128 * PACKET_SIZE;  // 128 packets max (was 2048!)  
        congestionWindow = 16 * PACKET_SIZE;      // 16 packets start (was 512!)
        slowStartThreshold = 64 * PACKET_SIZE;    // Exit slow start early
        estimatedBandwidthBps = 100_000_000;      // 100 Mbps conservative estimate
        smoothedRtt = 2_000_000; // 2ms realistic LAN RTT
        packetIntervalNs = 500;  // 500ns minimal pacing (not 0!)
        System.out.println("⚡ LOCAL NETWORK MODE - Ultra aggressive, no rate limiting");
    }
    
    public void enableWanMode() {
        isLocalNetwork = false;
        // Optimized WAN settings - more aggressive than before
        maxCongestionWindow = 128 * PACKET_SIZE;  // 128 packets (was 64)
        congestionWindow = 32 * PACKET_SIZE;      // 32 packets start (was 16)
        estimatedBandwidthBps = 50_000_000;       // 50 Mbps estimate
        updatePacingRate();
        System.out.println("📡 WAN MODE - Optimized settings for stability and performance");
    }
    
    /**
     * Current statistics
     */
    public String getStats() {
        long now = System.nanoTime();
        long elapsed = now - startTime;
        double throughputMbps = (totalBytesSent.get() * 8.0 * 1_000_000_000L) / (elapsed * 1_000_000.0);
        
        // Fix loss rate calculation - cap at 100%
        long totalSent = Math.max(1, totalPacketsSent.get());
        long totalLost = totalLossCount.get();
        double lossRate = Math.min(100.0, (totalLost * 100.0) / (totalSent + totalLost));
        
        return String.format(
            "State: %s, CWnd: %d pkts, BW: %.1f Mbps, RTT: %.1fms, " +
            "InFlight: %d pkts, Loss: %.2f%%, Throughput: %.1f Mbps",
            state,
            congestionWindow / PACKET_SIZE,
            estimatedBandwidthBps / 1_000_000.0,
            smoothedRtt / 1_000_000.0,
            packetsInFlight.get(),
            lossRate,
            throughputMbps
        );
    }
    
    /**
     * Get current sending capacity
     */
    public boolean canSendPacket() {
        return canSendPacket(PACKET_SIZE);
    }
    
    public boolean canSendPacket(int packetSize) {
        if (isLocalNetwork) return true;
        return bytesInFlight.get() + packetSize <= congestionWindow;
    }
    
    /**
     * Reset controller
     */
    public void reset() {
        congestionWindow = 32 * PACKET_SIZE;
        slowStartThreshold = Long.MAX_VALUE;
        state = CongestionState.SLOW_START;
        bytesInFlight.set(0);
        packetsInFlight.set(0);
        totalPacketsSent.set(0);
        totalBytesSent.set(0);
        totalLossCount.set(0);
        smoothedRtt = 100_000_000;
        rttVar = 50_000_000;
        minRtt = Long.MAX_VALUE;
        estimatedBandwidthBps = 10_000_000;
        startTime = System.nanoTime();
        lastStatsTime = startTime;
        updatePacingRate();
    }
    
    // Getters
    public long getCongestionWindow() { return congestionWindow; }
    public long getSmoothedRtt() { return smoothedRtt; }
    public long getPacingInterval() { return packetIntervalNs; }
    public CongestionState getState() { return state; }
}
