import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * FileTransferSender ve FileTransferReceiver integration test
 */
public class FileTransferTest {
    
    private static final String TEST_HOST = "127.0.0.1";
    private static final int SENDER_PORT = 9001;
    private static final int RECEIVER_PORT = 9002;
    
    public static void createTestFile() throws IOException {
        Path testFile = Paths.get("test_file.txt");
        
        // Test dosyası oluştur - 32MB büyük dosya
        StringBuilder content = new StringBuilder();
        // 32MB için yaklaşık 560,000 satır
        for(int i = 0; i < 560000; i++) {
            content.append("This is test line " + i + " for file transfer testing with more data.\n");
        }
        
        Files.write(testFile, content.toString().getBytes());
        System.out.println("✓ Test file created: " + testFile.toAbsolutePath());
        System.out.println("  Size: " + Files.size(testFile) + " bytes");
    }
    
    public static void cleanupTestFiles() {
        try {
            Files.deleteIfExists(Paths.get("test_file.txt"));
            Files.deleteIfExists(Paths.get("received_file.txt"));
            System.out.println("✓ Test files cleaned up");
        } catch (IOException e) {
            System.err.println("Cleanup error: " + e.getMessage());
        }
    }
    
    public static void runReceiver() {
        Thread receiverThread = new Thread(() -> {
            DatagramChannel receiverChannel = null;
            try {
                System.out.println("🔵 Starting receiver...");
                
                // Receiver channel setup
                receiverChannel = DatagramChannel.open();
                receiverChannel.bind(new InetSocketAddress(TEST_HOST, RECEIVER_PORT));
                receiverChannel.connect(new InetSocketAddress(TEST_HOST, SENDER_PORT));
                
                System.out.println("🔵 Receiver bound to " + TEST_HOST + ":" + RECEIVER_PORT);
                System.out.println("🔵 Receiver connected to sender " + TEST_HOST + ":" + SENDER_PORT);
                
                // FileTransferReceiver kullan
                FileTransferReceiver receiver = new FileTransferReceiver();
                receiver.channel = receiverChannel;
                receiver.filePath = Paths.get("received_file.txt");
                
                System.out.println("🔵 Starting file reception...");
                receiver.ReceiveData();
                
                System.out.println("🔵 Receiver completed!");
                
            } catch (Exception e) {
                System.err.println("❌ Receiver error: " + e.getMessage());
                e.printStackTrace();
            } finally {
                if (receiverChannel != null && receiverChannel.isOpen()) {
                    try {
                        receiverChannel.close();
                    } catch (IOException e) {
                        System.err.println("Error closing receiver channel: " + e.getMessage());
                    }
                }
            }
        }, "receiver-thread");
        
        receiverThread.setDaemon(false);
        receiverThread.start();
        
        // Receiver'ın başlaması için kısa bir süre bekle
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    public static void runSender() {
        Thread senderThread = new Thread(() -> {
            DatagramChannel senderChannel = null;
            try {
                System.out.println("🟢 Starting sender...");
                
                // Sender channel setup
                senderChannel = DatagramChannel.open();
                senderChannel.bind(new InetSocketAddress(TEST_HOST, SENDER_PORT));
                senderChannel.connect(new InetSocketAddress(TEST_HOST, RECEIVER_PORT));
                
                System.out.println("🟢 Sender bound to " + TEST_HOST + ":" + SENDER_PORT);
                System.out.println("🟢 Sender connected to receiver " + TEST_HOST + ":" + RECEIVER_PORT);
                
                // Receiver'ın hazır olması için bekle
                Thread.sleep(2000);
                
                // FileTransferSender kullan
                FileTransferSender sender = new FileTransferSender(senderChannel);
                Path testFile = Paths.get("test_file.txt");
                long fileId = 12345L;
                
                System.out.println("🟢 Starting file transmission...");
                System.out.println("🟢 File: " + testFile.toAbsolutePath());
                System.out.println("🟢 File ID: " + fileId);
                
                sender.sendFile(testFile, fileId);
                
                System.out.println("🟢 Sender completed!");
                
            } catch (Exception e) {
                System.err.println("❌ Sender error: " + e.getMessage());
                e.printStackTrace();
            } finally {
                if (senderChannel != null && senderChannel.isOpen()) {
                    try {
                        senderChannel.close();
                    } catch (IOException e) {
                        System.err.println("Error closing sender channel: " + e.getMessage());
                    }
                }
            }
        }, "sender-thread");
        
        senderThread.setDaemon(false);
        senderThread.start();
    }
    
    public static void verifyTransfer() throws IOException {
        Path originalFile = Paths.get("test_file.txt");
        Path receivedFile = Paths.get("received_file.txt");
        
        if (!Files.exists(receivedFile)) {
            System.err.println("❌ Received file does not exist!");
            return;
        }
        
        long originalSize = Files.size(originalFile);
        long receivedSize = Files.size(receivedFile);
        
        System.out.println("\n=== Transfer Verification ===");
        System.out.println("Original file size: " + originalSize + " bytes");
        System.out.println("Received file size: " + receivedSize + " bytes");
        
        if (originalSize == receivedSize) {
            // İçerik karşılaştırması
            byte[] originalContent = Files.readAllBytes(originalFile);
            byte[] receivedContent = Files.readAllBytes(receivedFile);
            
            boolean contentMatch = java.util.Arrays.equals(originalContent, receivedContent);
            
            if (contentMatch) {
                System.out.println("✅ File transfer SUCCESS! Content matches perfectly.");
            } else {
                System.out.println("❌ File transfer FAILED! Content mismatch.");
            }
        } else {
            System.out.println("❌ File transfer FAILED! Size mismatch.");
        }
    }
    
    public static void main(String[] args) {
        System.out.println("=== File Transfer Integration Test ===\n");
        
        try {
            // 1. Test dosyası oluştur
            createTestFile();
            
            // 2. Receiver'ı başlat
            System.out.println("\n--- Starting Receiver ---");
            runReceiver();
            
            // 3. Sender'ı başlat
            System.out.println("\n--- Starting Sender ---");
            runSender();
            
            // 4. Transfer'in tamamlanması için bekle
            System.out.println("\n--- Waiting for transfer completion ---");
            Thread.sleep(60000); // 60 saniye bekle - optimize edilmiş
            
            // 5. Sonuçları doğrula
            verifyTransfer();
            
            // 6. Temizlik
            Thread.sleep(2000); // Son işlemler için bekle
            cleanupTestFiles();
            
        } catch (Exception e) {
            System.err.println("❌ Test error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Thread pool'u kapat
            FileTransferSender.shutdownThreadPool();
            System.out.println("\n✓ Test completed!");
            System.exit(0);
        }
    }
}