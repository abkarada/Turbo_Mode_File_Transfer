import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Custom congestion control for P2P file transfer
 * Inspired by UDT congestion control algorithm
 */
public class CongestionController {
    
    // Congestion window size (in packets)
    private volatile double congestionWindowSize = 32.0; // Başlangıç pencere boyutu
    
    // Packet sending period in nanoseconds
    private volatile long packetSendingPeriodNs = 50_000; // 50μs başlangıç
    
    // Round trip time in microseconds
    private volatile long roundTripTimeUs = 20_000; // 20ms varsayılan
    
    // In-flight packet counter
    private final AtomicLong inFlightPackets = new AtomicLong(0);
    
    // Slow start phase flag
    private volatile boolean slowStartPhase = true;
    
    // Packet arrival rate (packets per second)
    private volatile long packetArrivalRate = 20_000; // 20k pps başlangıç
    
    // Link capacity estimation
    private volatile long estimatedLinkCapacity = 50_000; // 50k pps başlangıç
    
    // Loss detection
    private volatile boolean recentLoss = false;
    private volatile long lastLossTime = 0;
    
    // Statistics
    private final AtomicLong totalPacketsSent = new AtomicLong(0);
    private final AtomicLong totalAcksReceived = new AtomicLong(0);
    
    // Maximum in-flight packets (safety limit)
    private static final int MAX_IN_FLIGHT = 2048;
    
    public CongestionController() {
        // Initial conservative settings
    }
    
    /**
     * Check if we can send a packet (congestion window control)
     */
    public boolean canSendPacket() {
        long inFlight = inFlightPackets.get();
        return inFlight < Math.min(congestionWindowSize, MAX_IN_FLIGHT);
    }
    
    /**
     * Wait if necessary before sending packet (rate control)
     */
    public void rateLimitSend() {
        if (packetSendingPeriodNs > 0) {
            LockSupport.parkNanos(packetSendingPeriodNs);
        }
    }
    
    /**
     * Called when a packet is sent
     */
    public void onPacketSent() {
        inFlightPackets.incrementAndGet();
        totalPacketsSent.incrementAndGet();
    }
    
    /**
     * Called when ACK/NACK received (packet acknowledged)
     */
    public void onPacketAcked(int numAckedPackets) {
        inFlightPackets.addAndGet(-numAckedPackets);
        totalAcksReceived.addAndGet(numAckedPackets);
        
        if (slowStartPhase) {
            // Aggressive slow start: double exponential increase
            congestionWindowSize += numAckedPackets * 2; // 2x daha hızlı artış
            
            // Exit slow start much earlier for faster ramp-up
            if (congestionWindowSize > 64) { // 256'dan 64'e düşürdük
                slowStartPhase = false;
                // Start with higher rate
                packetSendingPeriodNs = 2_000; // 2μs (500k pps)
                packetArrivalRate = 500_000; // 500k pps varsayım
                System.out.println("🚀 Exiting aggressive slow start, window: " + (int)congestionWindowSize + ", rate: " + packetArrivalRate + " pps");
            }
        } else {
            // Aggressive congestion avoidance: faster increase
            congestionWindowSize += 2.0 / congestionWindowSize; // 2x daha hızlı
            
            if (!recentLoss) {
                // Increase sending rate more aggressively
                double increaseRatio = 1.01; // 1% increase (10x daha agresif)
                packetSendingPeriodNs = (long)(packetSendingPeriodNs / increaseRatio);
                
                // Lower bound - daha agresif minimum
                if (packetSendingPeriodNs < 500) { // Minimum 0.5μs (2M pps)
                    packetSendingPeriodNs = 500;
                }
            }
        }
        
        recentLoss = false;
    }
    
    /**
     * Called when packet loss detected
     */
    public void onPacketLoss(int lostPacketCount) {
        recentLoss = true;
        lastLossTime = System.currentTimeMillis();
        
        if (slowStartPhase) {
            // Exit slow start immediately
            slowStartPhase = false;
            packetSendingPeriodNs = Math.max(100_000, packetSendingPeriodNs * 2); // At least 100μs
            System.out.println("📉 Loss in slow start, window: " + (int)congestionWindowSize + " → " + (int)(congestionWindowSize/2));
        } else {
            // Multiplicative decrease
            congestionWindowSize = Math.max(16, congestionWindowSize * 0.875); // 12.5% decrease
            packetSendingPeriodNs = (long)(packetSendingPeriodNs * 1.125); // 12.5% increase in period
        }
        
        System.out.println("🔴 Packet loss detected (" + lostPacketCount + " packets), reduced rate");
    }
    
    /**
     * Update RTT measurement
     */
    public void updateRTT(long rttMicroseconds) {
        // Exponential weighted moving average
        if (roundTripTimeUs > 0) {
            roundTripTimeUs = (roundTripTimeUs * 7 + rttMicroseconds) / 8;
        } else {
            roundTripTimeUs = rttMicroseconds;
        }
        
        // Adjust congestion window based on RTT
        if (!slowStartPhase) {
            double bdp = (packetArrivalRate * roundTripTimeUs) / 1_000_000.0; // Bandwidth-delay product
            congestionWindowSize = Math.max(congestionWindowSize, bdp + 16);
        }
    }
    
    /**
     * Update packet arrival rate and link capacity
     */
    public void updateNetworkStats(long arrivalRate, long linkCapacity) {
        if (packetArrivalRate > 0) {
            packetArrivalRate = (packetArrivalRate * 7 + arrivalRate) / 8;
        } else {
            packetArrivalRate = arrivalRate;
        }
        
        if (estimatedLinkCapacity > 0) {
            estimatedLinkCapacity = (estimatedLinkCapacity * 7 + linkCapacity) / 8;
        } else {
            estimatedLinkCapacity = linkCapacity;
        }
    }
    
    /**
     * Get current congestion window size
     */
    public double getCongestionWindowSize() {
        return congestionWindowSize;
    }
    
    /**
     * Get current in-flight packet count
     */
    public long getInFlightPackets() {
        return inFlightPackets.get();
    }
    
    /**
     * Get current sending rate (packets per second)
     */
    public long getCurrentSendingRate() {
        if (packetSendingPeriodNs <= 0) return 0;
        return 1_000_000_000L / packetSendingPeriodNs;
    }
    
    /**
     * Get statistics
     */
    public String getStats() {
        return String.format("CW: %.1f, InFlight: %d, Rate: %d pps, RTT: %.1fms, %s",
                congestionWindowSize,
                inFlightPackets.get(),
                getCurrentSendingRate(),
                roundTripTimeUs / 1000.0,
                slowStartPhase ? "SlowStart" : "CongAvoid");
    }
    
    /**
     * Reset controller state
     */
    public void reset() {
        inFlightPackets.set(0);
        congestionWindowSize = 32.0;
        slowStartPhase = true;
        recentLoss = false;
        packetSendingPeriodNs = 50_000;
    }
    
    /**
     * Enable aggressive mode for local networks
     */
    public void enableAggressiveMode() {
        congestionWindowSize = 2048; // 4x daha büyük pencere
        packetSendingPeriodNs = 1_000; // 1μs (1M pps)
        packetArrivalRate = 1_000_000; // 1M pps varsayım
        slowStartPhase = false; // SlowStart'ı atla
        System.out.println("⚡ ULTRA Aggressive mode enabled - Window: " + (int)congestionWindowSize + ", Rate: 1M pps");
    }
}