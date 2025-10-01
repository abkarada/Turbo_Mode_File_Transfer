import java.nio.ByteBuffer;


public class BufferTestUtils {
    
       public static void printBufferHex(ByteBuffer buffer, String name) {
        ByteBuffer copy = buffer.duplicate();
        System.out.printf("%s [pos=%d, limit=%d, capacity=%d]: ", 
            name, copy.position(), copy.limit(), copy.capacity());
        
        while(copy.hasRemaining()) {
            System.out.printf("%02X ", copy.get());
        }
        System.out.println();
    }
    
        public static void printBufferState(ByteBuffer buffer, String name) {
        System.out.printf("%s: pos=%d, limit=%d, capacity=%d, remaining=%d%n",
            name, buffer.position(), buffer.limit(), buffer.capacity(), buffer.remaining());
    }
    
        public static void testHandShakePacket() {
        System.out.println("=== HandShake Packet Test ===");
        
        HandShake_Packet packet = new HandShake_Packet();
        
        packet.make_SYN(12345L, 1024, 10);
        printBufferState(packet.get_header(), "SYN Packet");
        printBufferHex(packet.get_header(), "SYN Content");
        
        ByteBuffer header = packet.get_header();
        System.out.println("Signal: " + HandShake_Packet.get_signal(header));
        System.out.println("File ID: " + HandShake_Packet.get_file_Id(header));
        System.out.println("File Size: " + HandShake_Packet.get_file_size(header));
        System.out.println("Total Seq: " + HandShake_Packet.get_total_seq(header));
        
        System.out.println();
    }
    
  
    public static void testSHA256Packet() {
        System.out.println("=== SHA256 Packet Test ===");
        
        SHA256_Packet packet = new SHA256_Packet();
        byte[] signature = new byte[32];
        
        for(int i = 0; i < 32; i++) {
            signature[i] = (byte)(i & 0xFF);
        }
        
        packet.fill_signature(12345L, 1024L, 10, signature);
        printBufferState(packet.get_header(), "SHA256 Packet");
        
        byte[] readSignature = SHA256_Packet.get_signature(packet.get_header());
        System.out.println("Signature match: " + java.util.Arrays.equals(signature, readSignature));
        
        System.out.println();
    }
    
    public static void testBufferPool() {
        System.out.println("=== Buffer Pool Test ===");
        
        BufferPool pool = new BufferPool(1024, 5, true);
        
        ByteBuffer buf1 = pool.acquire();
        ByteBuffer buf2 = pool.acquire();
        ByteBuffer buf3 = pool.acquire();
        
        System.out.println("Pool size after acquire: " + pool.getPoolSize());
        System.out.println("Total created: " + pool.getTotalCreated());
        
        pool.release(buf1);
        pool.release(buf2);
        
        System.out.println("Pool size after release: " + pool.getPoolSize());
        
        ByteBuffer buf4 = pool.acquire();
        System.out.println("Total created after reuse: " + pool.getTotalCreated());
        
        System.out.println();
    }
    
    public static void main(String[] args) {
        testHandShakePacket();
        testSHA256Packet();
        testBufferPool();
        
        System.out.println("All tests completed!");
    }
}