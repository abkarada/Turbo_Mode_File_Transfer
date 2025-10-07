import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Custom congestion control for P2P file transfer
 * Inspired by UDT congestion control algorithm
 */
public class CongestionController {
    
    // Congestion window size (in packets) - Daha küçük, kontrollü başlangıç
    private volatile double congestionWindowSize = 16.0; // Küçük başlangıç pencere boyutu
    
    // Packet sending period in nanoseconds - Kontrollü başlangıç
    private volatile long packetSendingPeriodNs = 25_000; // 25μs başlangıç = 40k pps
    
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
    
    // Maximum in-flight packets (safety limit) - Çok daha düşük
    private static final int MAX_IN_FLIGHT = 256; // Ağ tıkanmasını önlemek için düşük tutalım
    
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
            // Slow start: exponential increase
            congestionWindowSize += numAckedPackets;
            
            // Exit slow start when window gets moderate - daha erken çık
            if (congestionWindowSize > 64) {
                slowStartPhase = false;
                // Kontrollü rate-based control
                packetSendingPeriodNs = 10_000; // 10μs = 100k pps reasonable rate
                System.out.println("🚀 Exiting slow start, window: " + (int)congestionWindowSize + ", switching to controlled rate");
            }
        } else {
            // Smooth congestion avoidance: yavaş ve kontrollü artış
            congestionWindowSize += 0.5 / congestionWindowSize; // Daha yavaş artış
            
            if (!recentLoss) {
                // Çok az rate increase - stability için
                double increaseRatio = 1.0001; // 0.01% increase - çok küçük adımlar
                packetSendingPeriodNs = (long)(packetSendingPeriodNs / increaseRatio);
                
                // Reasonable lower bound - çok hızlı gitmesin
                if (packetSendingPeriodNs < 5_000) { // Minimum 5μs = 200k pps max
                    packetSendingPeriodNs = 5_000;
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
     * Enable smooth high-performance mode for local networks
     */
    public void enableAggressiveMode() {
        congestionWindowSize = 32; // Moderate başlangıç
        packetSendingPeriodNs = 15_000; // 15μs = ~67k pps reasonable start
        slowStartPhase = true; // Smooth scaling
        System.out.println("⚡ SMOOTH mode enabled - Controlled high-performance flow");
    }
}