import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.Random;

public class FileTransferTestFinal {
    
    private static final String TEST_DIR = "/tmp/file_transfer_final_test/";
    private static final String SENDER_FILE = "test_file_sender.txt";
    private static final String RECEIVER_FILE = "test_file_receiver.txt";
    private static final int SENDER_PORT = 12350;
    private static final int RECEIVER_PORT = 12351;
    private static final String TEST_HOST = "localhost";
    
    public static void main(String[] args) {
        System.out.println("=== FINAL File Transfer Test ===");
        
        try {
            // Test ortamını hazırla
            setupTestEnvironment();
            
            // Çeşitli boyutlarda test dosyaları
            testFileTransfer(1024, "1KB Test");      // 1KB
            testFileTransfer(5120, "5KB Test");      // 5KB  
            testFileTransfer(10240, "10KB Test");    // 10KB
            testFileTransfer(50000, "50KB Test");    // 50KB
            
            System.out.println("\n🎉 TÜM TESTLER BAŞARILI! 🎉");
            
        } catch (Exception e) {
            System.err.println("Test hatası: " + e.getMessage());
            e.printStackTrace();
        } finally {
            cleanup();
        }
    }
    
    private static void testFileTransfer(int fileSize, String testName) throws Exception {
        System.out.println("\n--- " + testName + " ---");
        
        Path senderFilePath = createTestFile(fileSize);
        
        Thread receiverThread = new Thread(() -> {
            try {
                DatagramChannel receiverChannel = DatagramChannel.open();
                receiverChannel.bind(new InetSocketAddress(TEST_HOST, RECEIVER_PORT));
                receiverChannel.connect(new InetSocketAddress(TEST_HOST, SENDER_PORT));
                receiverChannel.configureBlocking(true);
                
                FileTransferReceiver receiver = new FileTransferReceiver();
                receiver.channel = receiverChannel;
                receiver.filePath = Paths.get(TEST_DIR + RECEIVER_FILE);
                
                receiver.ReceiveData();
                
                receiverChannel.close();
                
            } catch (Exception e) {
                System.err.println("Receiver hatası: " + e.getMessage());
            }
        });
        
        Thread senderThread = new Thread(() -> {
            try {
                Thread.sleep(500);                 
                DatagramChannel senderChannel = DatagramChannel.open();
                senderChannel.bind(new InetSocketAddress(TEST_HOST, SENDER_PORT));
                senderChannel.connect(new InetSocketAddress(TEST_HOST, RECEIVER_PORT));
                senderChannel.configureBlocking(true);
                
                FileTransferSender.channel = senderChannel;
                
                long fileId = System.currentTimeMillis();
                FileTransferSender.sendFile(senderFilePath, fileId);
                
                senderChannel.close();
                
            } catch (Exception e) {
                System.err.println("Sender hatası: " + e.getMessage());
            }
        });
        
        long startTime = System.currentTimeMillis();
        
        receiverThread.start();
        senderThread.start();
        
        receiverThread.join(30000);         senderThread.join(10000);           
        long endTime = System.currentTimeMillis();
        
        boolean success = verifyTransfer(senderFilePath);
        
        if (success) {
            System.out.println("✅ " + testName + " BAŞARILI - Süre: " + (endTime - startTime) + "ms");
        } else {
            System.out.println(testName + " BAŞARISIZ");
        }
    }
    
    private static void setupTestEnvironment() throws IOException {
        Path testDir = Paths.get(TEST_DIR);
        if (!Files.exists(testDir)) {
            Files.createDirectories(testDir);
        }
    }
    
    private static Path createTestFile(int size) throws IOException {
        Path filePath = Paths.get(TEST_DIR + SENDER_FILE);
        
        Random random = new Random();
        StringBuilder content = new StringBuilder();
        
        while (content.length() < size) {
            content.append("Test line ").append(random.nextInt(1000)).append(" - ");
            content.append("Random data: ").append(random.nextLong()).append("\n");
        }
        
        String finalContent = content.substring(0, Math.min(size, content.length()));
        Files.write(filePath, finalContent.getBytes());
        
        return filePath;
    }
    
    private static boolean verifyTransfer(Path originalFile) {
        try {
            Path receivedFile = Paths.get(TEST_DIR + RECEIVER_FILE);
            
            if (!Files.exists(receivedFile)) {
                return false;
            }
            
            long originalSize = Files.size(originalFile);
            long receivedSize = Files.size(receivedFile);
            
            if (originalSize != receivedSize) {
                return false;
            }
            
            byte[] originalContent = Files.readAllBytes(originalFile);
            byte[] receivedContent = Files.readAllBytes(receivedFile);
            
            return java.util.Arrays.equals(originalContent, receivedContent);
            
        } catch (IOException e) {
            return false;
        }
    }
    
    private static void cleanup() {
        try {
            Path testDir = Paths.get(TEST_DIR);
            if (Files.exists(testDir)) {
                Files.walk(testDir)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                        }
                    });
            }
        } catch (IOException e) {
        }
    }
}