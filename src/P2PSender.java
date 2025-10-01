import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.DatagramChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * P2P File Transfer Sender - Farklı bilgisayarlardan test için
 * Kullanım: java P2PSender <bind_port> <target_ip> <target_port> <file_path>
 * Örnek: java P2PSender 8888 192.168.1.101 9999 test_file.txt
 */
public class P2PSender {
    
    public static void main(String[] args) {
        if (args.length < 4) {
            System.out.println("=== P2P File Transfer Sender ===");
            System.out.println("Kullanım: java P2PSender <bind_port> <target_ip> <target_port> <file_path>");
            System.out.println("");
            System.out.println("Parametreler:");
            System.out.println("  bind_port   : Kendi bilgisayarınızda bind edilecek port");
            System.out.println("  target_ip   : Hedef bilgisayarın IP adresi");
            System.out.println("  target_port : Hedef bilgisayarın port numarası");
            System.out.println("  file_path   : Gönderilecek dosyanın yolu");
            System.out.println("");
            System.out.println("Örnekler:");
            System.out.println("  java P2PSender 8888 192.168.1.101 9999 test_file.txt");
            System.out.println("  java P2PSender 7777 10.0.0.5 8888 document.pdf");
            System.out.println("  java P2PSender 0 192.168.1.200 9999 video.mp4  (0 = otomatik port)");
            return;
        }
        
        int bindPort;
        String targetIp = args[1];
        int targetPort;
        String filePath = args[3];
        
        try {
            bindPort = Integer.parseInt(args[0]);
            targetPort = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            System.err.println("❌ Hata: Port numaraları geçersiz");
            return;
        }
        
        if (bindPort < 0 || bindPort > 65535 || targetPort < 1 || targetPort > 65535) {
            System.err.println("❌ Hata: Port numaraları geçersiz (bind_port: 0-65535, target_port: 1-65535)");
            return;
        }
        
        // Dosya kontrolü
        Path file = Paths.get(filePath);
        if (!Files.exists(file)) {
            System.err.println("❌ Hata: Dosya bulunamadı: " + file.toAbsolutePath());
            return;
        }
        
        if (!Files.isRegularFile(file)) {
            System.err.println("❌ Hata: Bu bir dosya değil: " + file.toAbsolutePath());
            return;
        }
        
        DatagramChannel senderChannel = null;
        
        try {
            long fileSize = Files.size(file);
            double fileSizeMB = fileSize / (1024.0 * 1024.0);
            
            System.out.println("=== P2P File Transfer Sender ===");
            System.out.println("🟢 Sender başlatılıyor...");
            System.out.println("🟢 Bind Port: " + (bindPort == 0 ? "otomatik" : bindPort));
            System.out.println("🟢 Target: " + targetIp + ":" + targetPort);
            System.out.println("🟢 File: " + file.toAbsolutePath());
            System.out.println("🟢 File Size: " + fileSize + " bytes (" + String.format("%.2f", fileSizeMB) + " MB)");
            System.out.println("");
            
            // Channel setup with optimized buffers
            senderChannel = DatagramChannel.open();
            
            // Socket buffer boyutlarını artır (performance)
            senderChannel.setOption(StandardSocketOptions.SO_SNDBUF, 2 * 1024 * 1024); // 2MB send buffer
            senderChannel.setOption(StandardSocketOptions.SO_RCVBUF, 512 * 1024); // 512KB receive buffer
            
            InetSocketAddress bindAddress = new InetSocketAddress(bindPort);
            senderChannel.bind(bindAddress);
            
            // Actual bind port'u al (0 seçildiyse otomatik port alınır)
            int actualBindPort = ((InetSocketAddress) senderChannel.getLocalAddress()).getPort();
            System.out.println("✅ Socket başarıyla bind edildi - Port: " + actualBindPort);
            
            // Target'a connect
            InetSocketAddress targetAddress = new InetSocketAddress(targetIp, targetPort);
            senderChannel.connect(targetAddress);
            System.out.println("✅ Target'a bağlandı: " + targetAddress);
            System.out.println("");
            
            // FileTransferSender kullan
            FileTransferSender sender = new FileTransferSender(senderChannel);
            long fileId = System.currentTimeMillis(); // Unique file ID
            
            System.out.println("🟢 File transfer başlatılıyor...");
            System.out.println("🟢 File ID: " + fileId);
            System.out.println("🟢 Handshake yapılıyor ve transfer başlıyor...");
            System.out.println("");
            
            long startTime = System.currentTimeMillis();
            
            // Transfer'i başlat
            sender.sendFile(file, fileId);
            
            long endTime = System.currentTimeMillis();
            double transferTime = (endTime - startTime) / 1000.0;
            double throughputMBps = fileSizeMB / transferTime;
            
            System.out.println("");
            System.out.println("=== Transfer Tamamlandı ===");
            System.out.println("✅ Dosya başarıyla gönderildi!");
            System.out.println("📁 Dosya boyutu: " + fileSize + " bytes (" + String.format("%.2f", fileSizeMB) + " MB)");
            System.out.println("⏱️  Transfer süresi: " + String.format("%.2f", transferTime) + " saniye");
            System.out.println("🚀 Transfer hızı: " + String.format("%.2f", throughputMBps) + " MB/s");
            
        } catch (IOException e) {
            System.err.println("❌ IO Hatası: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("❌ Beklenmeyen hata: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Cleanup
            if (senderChannel != null && senderChannel.isOpen()) {
                try {
                    senderChannel.close();
                    System.out.println("🟢 Sender kapatıldı");
                } catch (IOException e) {
                    System.err.println("⚠️  Channel kapatma hatası: " + e.getMessage());
                }
            }
            
            // Thread pool'u kapat
            FileTransferSender.shutdownThreadPool();
            System.out.println("🟢 P2P Sender sona erdi");
        }
    }
}