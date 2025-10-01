import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;

public class SimpleUnitTests {
    
    public static void main(String[] args) {
        System.out.println("=== Simple Unit Tests ===\n");
        
        testHandShakePacket();
        testSHA256Packet();
        testCRC32CPacket();
        testNackFrame();
        testChannelSetup();
        
        System.out.println("\n=== Tüm testler tamamlandı ===");
    }
    
    private static void testHandShakePacket() {
        System.out.println("🧪 HandShake_Packet testi...");
        try {
            HandShake_Packet packet = new HandShake_Packet();
            
            // SYN paketi test et
            long fileId = 12345L;
            int fileSize = 1000;
            int totalSeq = 10;
            
            packet.make_SYN(fileId, fileSize, totalSeq);
            
            // Veriyi geri oku
            long readFileId = HandShake_Packet.get_file_Id(packet.get_header());
            int readFileSize = HandShake_Packet.get_file_size(packet.get_header());
            int readTotalSeq = HandShake_Packet.get_total_seq(packet.get_header());
            byte signal = HandShake_Packet.get_signal(packet.get_header());
            
            if (readFileId == fileId && readFileSize == fileSize && 
                readTotalSeq == totalSeq && signal == HandShake_Packet.SYN) {
                System.out.println("✅ HandShake_Packet SYN testi BAŞARILI");
            } else {
                System.out.println("❌ HandShake_Packet SYN testi BAŞARISIZ");
                System.out.println("  Beklenen: fileId=" + fileId + ", fileSize=" + fileSize + ", totalSeq=" + totalSeq);
                System.out.println("  Okunan: fileId=" + readFileId + ", fileSize=" + readFileSize + ", totalSeq=" + readTotalSeq);
            }
            
        } catch (Exception e) {
            System.out.println("❌ HandShake_Packet testi HATA: " + e.getMessage());
        }
    }
    
    private static void testSHA256Packet() {
        System.out.println("🧪 SHA256_Packet testi...");
        try {
            SHA256_Packet packet = new SHA256_Packet();
            
            // Test signature oluştur
            byte[] testSignature = new byte[32];
            for (int i = 0; i < 32; i++) {
                testSignature[i] = (byte) (i % 256);
            }
            
            long fileId = 54321L;
            long fileSize = 2000L;
            int totalSeq = 20;
            
            packet.fill_signature(fileId, fileSize, totalSeq, testSignature);
            
            // Veriyi geri oku
            long readFileId = SHA256_Packet.get_fileId(packet.get_header());
            long readFileSize = SHA256_Packet.get_fileSize(packet.get_header());
            int readTotalSeq = SHA256_Packet.get_total_seq(packet.get_header());
            byte[] readSignature = SHA256_Packet.get_signature(packet.get_header());
            
            boolean signatureMatch = java.util.Arrays.equals(testSignature, readSignature);
            
            if (readFileId == fileId && readFileSize == fileSize && 
                readTotalSeq == totalSeq && signatureMatch) {
                System.out.println("✅ SHA256_Packet testi BAŞARILI");
            } else {
                System.out.println("❌ SHA256_Packet testi BAŞARISIZ");
                System.out.println("  Signature match: " + signatureMatch);
            }
            
        } catch (Exception e) {
            System.out.println("❌ SHA256_Packet testi HATA: " + e.getMessage());
        }
    }
    
    private static void testCRC32CPacket() {
        System.out.println("🧪 CRC32C_Packet testi...");
        try {
            CRC32C_Packet packet = new CRC32C_Packet();
            
            long fileId = 98765L;
            int seqNo = 5;
            int totalSeq = 100;
            int payloadLen = 1200;
            int crc32c = 0x12345678;
            
            packet.fillHeader(fileId, seqNo, totalSeq, payloadLen, crc32c);
            
            // Veriyi geri oku
            long readFileId = CRC32C_Packet.fileId(packet.headerBuffer());
            int readSeqNo = CRC32C_Packet.seqNo(packet.headerBuffer());
            int readTotalSeq = CRC32C_Packet.total(packet.headerBuffer());
            int readPayloadLen = CRC32C_Packet.plen(packet.headerBuffer());
            int readCrc32c = CRC32C_Packet.crc32(packet.headerBuffer());
            
            if (readFileId == fileId && readSeqNo == seqNo && readTotalSeq == totalSeq &&
                readPayloadLen == payloadLen && readCrc32c == crc32c) {
                System.out.println("✅ CRC32C_Packet testi BAŞARILI");
            } else {
                System.out.println("❌ CRC32C_Packet testi BAŞARISIZ");
            }
            
        } catch (Exception e) {
            System.out.println("❌ CRC32C_Packet testi HATA: " + e.getMessage());
        }
    }
    
    private static void testNackFrame() {
        System.out.println("🧪 NackFrame testi...");
        try {
            long fileId = 11111L;
            int baseSeq = 50;
            long mask = 0xAAAAAAAAL; // Alternatif pattern
            
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(NackFrame.SIZE);
            buffer.order(java.nio.ByteOrder.BIG_ENDIAN);
            
            // Manuel olarak frame oluştur
            buffer.putLong(0, fileId);
            buffer.putInt(8, baseSeq);
            buffer.putLong(12, mask);
            
            // Veriyi geri oku
            long readFileId = NackFrame.fileId(buffer);
            int readBaseSeq = NackFrame.baseSeq(buffer);
            long readMask = NackFrame.mask64(buffer);
            
            if (readFileId == fileId && readBaseSeq == baseSeq && readMask == mask) {
                System.out.println("✅ NackFrame testi BAŞARILI");
            } else {
                System.out.println("❌ NackFrame testi BAŞARISIZ");
            }
            
        } catch (Exception e) {
            System.out.println("❌ NackFrame testi HATA: " + e.getMessage());
        }
    }
    
    private static void testChannelSetup() {
        System.out.println("🧪 DatagramChannel setup testi...");
        try {
            DatagramChannel channel = DatagramChannel.open();
            channel.configureBlocking(true);
            
            // Test port bağlama
            InetSocketAddress address = new InetSocketAddress("localhost", 0); // 0 = random port
            channel.bind(address);
            
            InetSocketAddress localAddress = (InetSocketAddress) channel.getLocalAddress();
            System.out.println("✅ DatagramChannel testi BAŞARILI - Port: " + localAddress.getPort());
            
            channel.close();
            
        } catch (IOException e) {
            System.out.println("❌ DatagramChannel testi HATA: " + e.getMessage());
        }
    }
}