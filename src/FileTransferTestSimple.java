import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

public class FileTransferTestSimple {
    
    private static final String TEST_DIR = "/tmp/file_transfer_test_simple/";
    private static final String SENDER_FILE = "test_file_sender.txt";
    private static final String RECEIVER_FILE = "test_file_receiver.txt";
    private static final int SENDER_PORT = 12346;
    private static final int RECEIVER_PORT = 12347;
    private static final String TEST_HOST = "localhost";
    
    public static void main(String[] args) {
        System.out.println("=== Simple File Transfer Test ===");
        
        try {
            // Test ortamını hazırla
            setupTestEnvironment();
            
            // Test dosyasını oluştur
            Path senderFilePath = createTestFile();
            
            System.out.println("Manuel test için:");
            System.out.println("1. İlk terminalde: java FileTransferTestSimple receiver");
            System.out.println("2. İkinci terminalde: java FileTransferTestSimple sender");
            
            if (args.length > 0 && args[0].equals("receiver")) {
                runReceiver();
            } else if (args.length > 0 && args[0].equals("sender")) {
                runSender(senderFilePath);
            } else {
                // Otomatik test - daha basit UDP setup
                testWithConnectedChannels();
            }
            
        } catch (Exception e) {
            System.err.println("Test hatası: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void testWithConnectedChannels() throws Exception {
        System.out.println("Connected channels ile test...");
        
        Path senderFilePath = createTestFile();
        
        // Her iki taraf da connect edilmiş channel kullanacak
        Thread receiverThread = new Thread(() -> {
            try {
                DatagramChannel receiverChannel = DatagramChannel.open();
                receiverChannel.bind(new InetSocketAddress(TEST_HOST, RECEIVER_PORT));
                receiverChannel.connect(new InetSocketAddress(TEST_HOST, SENDER_PORT));
                receiverChannel.configureBlocking(true);
                
                FileTransferReceiver receiver = new FileTransferReceiver();
                receiver.channel = receiverChannel;
                receiver.filePath = Paths.get(TEST_DIR + RECEIVER_FILE);
                
                System.out.println("Receiver hazır ve bekliyor...");
                receiver.ReceiveData();
                
                // Channel'ı kapatma - büyük uygulamada başka yerler de kullanacak
                System.out.println("Receiver tamamlandı");
                
            } catch (Exception e) {
                System.err.println("Receiver hatası: " + e.getMessage());
                e.printStackTrace();
            }
        });
        
        Thread senderThread = new Thread(() -> {
            try {
                Thread.sleep(1000); // Receiver'ın hazır olması için bekle
                
                DatagramChannel senderChannel = DatagramChannel.open();
                senderChannel.bind(new InetSocketAddress(TEST_HOST, SENDER_PORT));
                senderChannel.connect(new InetSocketAddress(TEST_HOST, RECEIVER_PORT));
                senderChannel.configureBlocking(true);
                
                FileTransferSender.channel = senderChannel;
                
                long fileId = System.currentTimeMillis();
                System.out.println("Sender dosyayı gönderiyor... File ID: " + fileId);
                
                FileTransferSender.sendFile(senderFilePath, fileId);
                
                // Channel'ı kapatma - büyük uygulamada başka yerler de kullanacak
                System.out.println("Sender tamamlandı");
                
            } catch (Exception e) {
                System.err.println("Sender hatası: " + e.getMessage());
                e.printStackTrace();
            }
        });
        
        receiverThread.start();
        senderThread.start();
        
        receiverThread.join(120000); // 120 saniye timeout
        senderThread.join(30000);   // 30 saniye timeout
        
        // Sonuçları kontrol et
        verifyTransfer(senderFilePath);
    }
    
    private static void setupTestEnvironment() throws IOException {
        Path testDir = Paths.get(TEST_DIR);
        if (!Files.exists(testDir)) {
            Files.createDirectories(testDir);
        }
        System.out.println("Test ortamı hazırlandı: " + TEST_DIR);
    }
    
    private static Path createTestFile() throws IOException {
        Path filePath = Paths.get(TEST_DIR + SENDER_FILE);
        
        // 5KB test dosyası oluştur (daha küçük ve hızlı test için)
        Random random = new Random();
        StringBuilder content = new StringBuilder();
        
        for (int i = 0; i < 500; i++) {
            content.append("Test satırı ").append(i).append(" - ");
            content.append("Random: ").append(random.nextInt(1000)).append("\n");
        }
        
        Files.write(filePath, content.toString().getBytes());
        System.out.println("Test dosyası oluşturuldu: " + filePath + " (" + Files.size(filePath) + " bytes)");
        
        return filePath;
    }
    
    private static void runReceiver() {
        try {
            System.out.println("Receiver modu başlatılıyor...");
            
            DatagramChannel channel = DatagramChannel.open();
            channel.bind(new InetSocketAddress(TEST_HOST, RECEIVER_PORT));
            channel.connect(new InetSocketAddress(TEST_HOST, SENDER_PORT));
            channel.configureBlocking(true);
            
            FileTransferReceiver receiver = new FileTransferReceiver();
            receiver.channel = channel;
            receiver.filePath = Paths.get(TEST_DIR + RECEIVER_FILE);
            
            System.out.println("Receiver dinliyor: " + TEST_HOST + ":" + RECEIVER_PORT);
            System.out.println("Sender'dan bağlantı bekleniyor...");
            
            receiver.ReceiveData();
            
            channel.close();
            System.out.println("Receiver tamamlandı");
            
        } catch (Exception e) {
            System.err.println("Receiver hatası: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void runSender(Path filePath) {
        try {
            System.out.println("Sender modu başlatılıyor...");
            
            DatagramChannel channel = DatagramChannel.open();
            channel.bind(new InetSocketAddress(TEST_HOST, SENDER_PORT));
            channel.connect(new InetSocketAddress(TEST_HOST, RECEIVER_PORT));
            channel.configureBlocking(true);
            
            FileTransferSender.channel = channel;
            
            long fileId = System.currentTimeMillis();
            System.out.println("Dosya gönderiliyor - File ID: " + fileId);
            
            FileTransferSender.sendFile(filePath, fileId);
            
            channel.close();
            System.out.println("Sender tamamlandı");
            
        } catch (IOException | NoSuchAlgorithmException e) {
            System.err.println("Sender hatası: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void verifyTransfer(Path originalFile) {
        try {
            Path receivedFile = Paths.get(TEST_DIR + RECEIVER_FILE);
            
            if (!Files.exists(receivedFile)) {
                System.err.println("❌ HATA: Alınan dosya bulunamadı!");
                return;
            }
            
            long originalSize = Files.size(originalFile);
            long receivedSize = Files.size(receivedFile);
            
            System.out.println("\n=== Transfer Sonuçları ===");
            System.out.println("Orijinal dosya boyutu: " + originalSize + " bytes");
            System.out.println("Alınan dosya boyutu: " + receivedSize + " bytes");
            
            if (originalSize == receivedSize) {
                // İçerik karşılaştırması
                byte[] originalContent = Files.readAllBytes(originalFile);
                byte[] receivedContent = Files.readAllBytes(receivedFile);
                
                boolean contentMatch = java.util.Arrays.equals(originalContent, receivedContent);
                
                if (contentMatch) {
                    System.out.println("✅ TEST BAŞARILI: Dosyalar tamamen eşleşiyor!");
                } else {
                    System.out.println("❌ TEST BAŞARISIZ: Dosya boyutları aynı ama içerik farklı!");
                }
            } else {
                System.out.println("❌ TEST BAŞARISIZ: Dosya boyutları farklı!");
            }
            
        } catch (IOException e) {
            System.err.println("Doğrulama hatası: " + e.getMessage());
        }
    }
}