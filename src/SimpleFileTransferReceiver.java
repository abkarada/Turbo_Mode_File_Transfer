import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.nio.MappedByteBuffer;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.LockSupport;
import java.util.BitSet;

public class SimpleFileTransferReceiver {	
	public  DatagramChannel channel;
	public long fileId;
	public int file_size;
	public int total_seq;
	
	public FileChannel fc;
	public Path filePath;
	public MappedByteBuffer mem_buf;
	public static final long MAX_FILE_SIZE = 256L << 20;
	public static final int SLICE_SIZE = 1200;
	public static final int HEADER_SIZE = 22;
	
	// Simple tracking
	private BitSet receivedPackets;
	private volatile boolean transferComplete = false;
	private long transferStartTime = 0;
	private long transferEndTime = 0;
	
	public  boolean handshake()
	{
		if(channel == null){
			throw new IllegalStateException("Datagram Channel is null you must bind and connect first");
		}
		ByteBuffer rcv_syn = ByteBuffer.allocateDirect(HandShake_Packet.HEADER_SIZE)
			.order(ByteOrder.BIG_ENDIAN);

		rcv_syn.clear();
		int r = 0;

		// Handshake timeout ekle (30 saniye)
		long handshakeDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
		
		SocketAddress senderAddress = null;
		try{
			do{
				if(System.nanoTime() > handshakeDeadline) {
					System.err.println("Handshake timeout after 30 seconds");
					return false;
				}
				
				senderAddress = channel.receive(rcv_syn);
				if(senderAddress == null) {
					LockSupport.parkNanos(1_000_000); // 1ms bekleme
					continue;
				}
				
				r = rcv_syn.position();
				if( r == 0 || r != HandShake_Packet.HEADER_SIZE || HandShake_Packet.get_signal(rcv_syn) != HandShake_Packet.SYN) {
					rcv_syn.clear();
					LockSupport.parkNanos(1_000_000); // 1ms bekleme
				}
			}while( r == 0 || r != HandShake_Packet.HEADER_SIZE || HandShake_Packet.get_signal(rcv_syn) != HandShake_Packet.SYN);
		}catch(IOException e ){
			System.err.println("IO Error during handshake: " + e);
			return false;
		}
		rcv_syn.flip();
		fileId = HandShake_Packet.get_file_Id(rcv_syn);
		file_size = HandShake_Packet.get_file_size(rcv_syn);
		total_seq = HandShake_Packet.get_total_seq(rcv_syn);
		
		if(fileId != 0 && file_size != 0 && total_seq != 0)
		 {
			 // Sender'a bağlan
			 try {
				 channel.connect(senderAddress);
				 System.out.println("🔗 Sender'a bağlandı: " + senderAddress);
			 } catch(IOException e) {
				 System.err.println("❌ Sender'a bağlanma hatası: " + e);
				 return false;
			 }
			 
			 HandShake_Packet ack_pkt = new HandShake_Packet();
			ack_pkt.make_ACK(fileId, file_size, total_seq);
			try{
			while(channel.write(ack_pkt.get_header().duplicate()) == 0)
			{
				ack_pkt.resetForRetransmitter();
				LockSupport.parkNanos(1_000_000); // 1ms bekleme
			}}catch(IOException e){System.err.println("IO ERROR: " + e);}
		 	
			rcv_syn.clear();

			int t;
			
			try{
				do{
				t = channel.read(rcv_syn);
				if(t == 0 || t < 9 || t > 13) 
					LockSupport.parkNanos(1_000_000); // 1ms bekleme
				}while(t == 0 || t < 9 || t > 13);
			}catch(IOException e){
				System.err.println("SYN + ACK Packet State Error: " + e);
			}
			if(HandShake_Packet.get_signal(rcv_syn) == 0x11 && HandShake_Packet.get_file_Id(rcv_syn) == fileId) return true;

		 }

		return false;
	}
	
	public boolean initialize()
	{
		try{
			if(handshake()){
				fc = FileChannel.open(filePath, StandardOpenOption.CREATE 
						, StandardOpenOption.READ
						, StandardOpenOption.WRITE 
						,StandardOpenOption.SYNC);
						
				mem_buf = fc.map(FileChannel.MapMode.READ_WRITE, 0, file_size);
				
				// Initialize tracking
				receivedPackets = new BitSet(total_seq);
				
				System.out.println("Handshake completed, file size: " + file_size + ", total packets: " + total_seq);
				return true;
			}
		}catch(IOException e){
			System.err.println("IO Error during initialization: " + e);
		}
		return false;
	}
	
	// SIMPLE receive without complex NackSender
	public void ReceiveData(){
	
	if(initialize()){
		
		transferStartTime = System.currentTimeMillis();
		System.out.println("📊 Simple data transfer başladı");
		
		ByteBuffer buffer = ByteBuffer.allocateDirect(HEADER_SIZE + SLICE_SIZE);
		int packetsReceived = 0;
		long lastProgressTime = System.currentTimeMillis();
		
		while(!transferComplete && packetsReceived < total_seq) {
			buffer.clear();
			
			try {
				int bytesRead = channel.read(buffer);
				if(bytesRead <= 0) {
					LockSupport.parkNanos(100_000); // 100μs bekleme
					continue;
				}
				
				buffer.flip();
				
				if(bytesRead < HEADER_SIZE) {
					continue; // Too small
				}
				
				// Parse packet
				long packetFileId = buffer.getLong(0);
				if(packetFileId != fileId) {
					continue; // Wrong file
				}
				
				int seqNo = buffer.getInt(8);
				int totalSeqInPacket = buffer.getInt(12);
				int payloadLength = buffer.getInt(16);
				int crc = buffer.getInt(20);
				
				if(seqNo < 0 || seqNo >= total_seq) {
					continue; // Invalid sequence
				}
				
				if(receivedPackets.get(seqNo)) {
					continue; // Already received
				}
				
				// Payload validation
				if(bytesRead != HEADER_SIZE + payloadLength) {
					System.err.println("Packet size mismatch: expected " + (HEADER_SIZE + payloadLength) + ", got " + bytesRead);
					continue;
				}
				
				// Simple CRC check (optional for speed)
				ByteBuffer payload = buffer.slice(HEADER_SIZE, payloadLength);
				
				// Write to memory mapped file
				int offset = seqNo * SLICE_SIZE;
				if(offset + payloadLength <= mem_buf.capacity()) {
					mem_buf.position(offset);
					mem_buf.put(payload);
					
					receivedPackets.set(seqNo);
					packetsReceived++;
					
					// Progress every 2 seconds
					long now = System.currentTimeMillis();
					if(now - lastProgressTime > 2000) {
						double progress = (double)packetsReceived / total_seq * 100;
						System.out.printf("📥 Progress: %.1f%% (%d/%d packets)\n", 
							progress, packetsReceived, total_seq);
						lastProgressTime = now;
					}
				}
				
			} catch(IOException e) {
				System.err.println("Read error: " + e.getMessage());
				LockSupport.parkNanos(1_000_000); // 1ms bekleme
			}
		}
		
		transferEndTime = System.currentTimeMillis();
		transferComplete = true;
		
		// Send completion signal
		try {
			ByteBuffer completionFrame = ByteBuffer.allocate(8);
			completionFrame.putInt(0xDEADBEEF);
			completionFrame.putInt((int)fileId);
			completionFrame.flip();
			
			channel.write(completionFrame);
			System.out.println("✅ Transfer completion signal sent to sender");
			
			Thread.sleep(100); // Signal gönderilmesi için bekle
			
		} catch(Exception e) {
			System.err.println("Failed to send completion signal: " + e);
		}
		
		System.out.println("File transfer completed successfully!");
		
		try {
			if(fc != null) fc.close();
		} catch(IOException e) {
			System.err.println("File close error: " + e);
		}

	}else{
		System.out.println("Initialization Error");
		}
	}
	
	public double getTransferTimeSeconds() {
		if(transferStartTime == 0 || transferEndTime == 0) {
			return 0.0;
		}
		return (transferEndTime - transferStartTime) / 1000.0;
	}
}