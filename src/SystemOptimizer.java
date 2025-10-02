/**
 * System-level optimizations for high-performance networking
 * Includes thread priority, CPU affinity hints, and system tuning
 */
public class SystemOptimizer {
    
    private static boolean optimizationApplied = false;
    
    /**
     * Apply high-performance optimizations to current thread
     */
    public static void optimizeCurrentThread(String threadName) {
        try {
            Thread currentThread = Thread.currentThread();
            
            // Set high priority (requires appropriate permissions)
            try {
                currentThread.setPriority(Thread.MAX_PRIORITY);
                System.out.println("🚀 Thread '" + threadName + "' priority set to MAX");
            } catch(SecurityException e) {
                System.out.println("⚠️  Could not set high priority for '" + threadName + "': " + e.getMessage());
            }
            
            // Set thread name for easier profiling
            if(threadName != null && !threadName.isEmpty()) {
                currentThread.setName(threadName);
            }
            
            // Suggest system-level optimizations (printed once)
            if(!optimizationApplied) {
                printSystemOptimizationSuggestions();
                optimizationApplied = true;
            }
            
        } catch(Exception e) {
            System.err.println("Thread optimization error for '" + threadName + "': " + e.getMessage());
        }
    }
    
    /**
     * Optimize thread for network I/O operations
     */
    public static void optimizeNetworkThread(String threadName) {
        optimizeCurrentThread("net-" + threadName);
        
        // Additional network-specific optimizations
        try {
            // Hint to JVM that this thread does network I/O
            System.setProperty("java.net.preferIPv4Stack", "true");
            System.setProperty("java.net.useSystemProxies", "false");
        } catch(Exception e) {
            // Ignore
        }
    }
    
    /**
     * Optimize thread for file I/O operations  
     */
    public static void optimizeFileIOThread(String threadName) {
        optimizeCurrentThread("io-" + threadName);
    }
    
    /**
     * Print system-level optimization suggestions
     */
    private static void printSystemOptimizationSuggestions() {
        System.out.println("\n🔧 SYSTEM OPTIMIZATION SUGGESTIONS:");
        System.out.println("For maximum performance, run these commands as root:");
        System.out.println("┌─ Network Buffers:");
        System.out.println("│  echo 'net.core.rmem_max = 134217728' >> /etc/sysctl.conf");
        System.out.println("│  echo 'net.core.wmem_max = 134217728' >> /etc/sysctl.conf");
        System.out.println("│  echo 'net.core.netdev_max_backlog = 30000' >> /etc/sysctl.conf");
        System.out.println("│  sysctl -p");
        System.out.println("├─ CPU Frequency:");
        System.out.println("│  echo performance > /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor");
        System.out.println("├─ Process Priority:");
        System.out.println("│  nice -n -10 java P2PSender ...");
        System.out.println("│  nice -n -10 java P2PReceiver ...");
        System.out.println("└─ Memory:");
        System.out.println("   echo never > /sys/kernel/mm/transparent_hugepage/enabled");
        System.out.println("   sysctl vm.swappiness=1");
        System.out.println();
    }
    
    /**
     * Get current system performance status 
     */
    public static String getSystemStatus() {
        StringBuilder status = new StringBuilder();
        
        // Available processors
        int processors = Runtime.getRuntime().availableProcessors();
        status.append("CPUs: ").append(processors);
        
        // Memory info
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        
        status.append(", Heap: ")
              .append((totalMemory - freeMemory) / (1024*1024))
              .append("/")
              .append(maxMemory / (1024*1024))
              .append(" MB");
        
        return status.toString();
    }
    
    /**
     * Force garbage collection and print memory status
     */
    public static void optimizeMemory(String context) {
        System.gc();
        System.out.println("🧹 GC forced (" + context + ") - " + getSystemStatus());
    }
}