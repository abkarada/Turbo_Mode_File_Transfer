import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Test dosyası oluşturucu
 * Kullanım: java CreateTestFile <file_name> <size_mb>
 */
public class CreateTestFile {
    
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("=== Test Dosyası Oluşturucu ===");
            System.out.println("Kullanım: java CreateTestFile <file_name> <size_mb>");
            System.out.println("");
            System.out.println("Örnekler:");
            System.out.println("  java CreateTestFile test_1mb.txt 1");
            System.out.println("  java CreateTestFile test_10mb.txt 10");
            System.out.println("  java CreateTestFile big_file.txt 50");
            return;
        }
        
        String fileName = args[0];
        int sizeMB;
        
        try {
            sizeMB = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("❌ Hata: Boyut geçersiz: " + args[1]);
            return;
        }
        
        if (sizeMB < 1 || sizeMB > 256) {
            System.err.println("❌ Hata: Boyut 1-256 MB arasında olmalı");
            return;
        }
        
        try {
            Path testFile = Paths.get(fileName);
            
            System.out.println("🔧 Test dosyası oluşturuluyor...");
            System.out.println("📁 Dosya: " + testFile.toAbsolutePath());
            System.out.println("📏 Boyut: " + sizeMB + " MB");
            
            // İçerik oluştur
            StringBuilder content = new StringBuilder();
            int totalLines = sizeMB * 17500; // Yaklaşık 1MB için 17500 satır
            
            for (int i = 0; i < totalLines; i++) {
                content.append("Test line " + i + " - P2P file transfer test data with some additional content for realistic size.\n");
                
                // Progress göster
                if (i > 0 && i % 10000 == 0) {
                    double progress = (double) i / totalLines * 100;
                    System.out.printf("\r🔧 İlerleme: %.1f%%", progress);
                }
            }
            
            // Dosyayı yaz
            Files.write(testFile, content.toString().getBytes("UTF-8"));
            
            long actualSize = Files.size(testFile);
            double actualSizeMB = actualSize / (1024.0 * 1024.0);
            
            System.out.println("\r✅ Test dosyası oluşturuldu!");
            System.out.println("📁 Dosya: " + testFile.toAbsolutePath());
            System.out.println("📏 Gerçek boyut: " + actualSize + " bytes (" + String.format("%.2f", actualSizeMB) + " MB)");
            
        } catch (IOException e) {
            System.err.println("❌ Dosya oluşturma hatası: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("❌ Beklenmeyen hata: " + e.getMessage());
            e.printStackTrace();
        }
    }
}