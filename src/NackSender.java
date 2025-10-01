import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.BitSet;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.CRC32C;

public class NackSender implements Runnable{
	public final long fileId;
	public final int file_size;
	public final int total_seq;
	public final DatagramChannel channel;
	public final BitSet recv;
	public final NackFrame frame;
	public final MappedByteBuffer mem_buf;
	
	// Completion callback
	public volatile Runnable onTransferComplete = null;

	public NackSender(DatagramChannel channel, long fileId, int file_size,
			int total_seq, MappedByteBuffer mem_buf){
		this.channel = channel;
		this.fileId = fileId;
		this.file_size = file_size;
		this.total_seq = total_seq;
		this.mem_buf = mem_buf;
		this.recv = new BitSet(total_seq);
		this.frame = new NackFrame();
	}

	public volatile int cum_Ack = 0;
	private volatile boolean transferCompleted = false;
	public final int CRC32C_HEADER_SIZE = 22;
	public final int PAYLOAD_SIZE = 1200;
	public final int TOTAL_PACKET_SIZE = CRC32C_HEADER_SIZE + PAYLOAD_SIZE;

    	public static final int OFF_FILE_ID  = 0;
    	public static final int OFF_SEQ      = 8;
    	public static final int OFF_TOTAL    = 12;
    	public static final int OFF_PLEN     = 16;


	public  ByteBuffer buf = ByteBuffer.allocateDirect(CRC32C_HEADER_SIZE + PAYLOAD_SIZE).order(ByteOrder.BIG_ENDIAN);
	public CRC32C crc = new CRC32C();

	public synchronized boolean isTransferComplete(){
		return recv.cardinality() == total_seq;	
	}
	
	public boolean isTransferCompleted() {
		return transferCompleted;
	}

	private void updateCumulativeAck() {
    	synchronized(this) {
    	    while(cum_Ack < total_seq && recv.get(cum_Ack)) {
   	         cum_Ack++;
  	      }
	    if(isTransferComplete() && !transferCompleted){
			transferCompleted = true;
			stopNackLoop();
			System.out.println("File transfer completed successfully! Shutting down receiver...");
			
			// Completion callback'ı çağır
			if(onTransferComplete != null) {
				try {
					onTransferComplete.run();
				} catch(Exception e) {
					System.err.println("Transfer completion callback error: " + e);
				}
			}
			return;
	    }
 	   }
	}

	public void onData(ByteBuffer fullPacket){
		// Packet validation
		if(fullPacket == null || fullPacket.remaining() < CRC32C_HEADER_SIZE) {
			System.err.println("Invalid packet: null or too small");
			return;
		}
		
		int seqNo = CRC32C_Packet.seqNo(fullPacket);
		int receivedCrc = CRC32C_Packet.crc32(fullPacket);
		int payloadLen = CRC32C_Packet.plen(fullPacket);
		
		// Sequence number validation
		if(seqNo < 0 || seqNo >= total_seq) {
			System.err.println("Invalid sequence number: " + seqNo + " (total: " + total_seq + ")");
			return;
		}
		
		// Payload length validation
		if(payloadLen <= 0 || payloadLen > PAYLOAD_SIZE) {
			System.err.println("Invalid payload length: " + payloadLen);
			return;
		}
		
		// Packet size validation
		if(fullPacket.remaining() < CRC32C_HEADER_SIZE + payloadLen) {
			System.err.println("Packet too small for declared payload length");
			return;
		}
		
		// Extract payload - safely slice
		ByteBuffer payload = fullPacket.slice(CRC32C_HEADER_SIZE, payloadLen);
		

		// CRC validation
		crc.reset();
		crc.update(payload.duplicate());
		int calculatedCrc = (int) crc.getValue();
		
		if(calculatedCrc == receivedCrc){
			int off = seqNo * PAYLOAD_SIZE;
			
			// Buffer bounds check - daha kesin
			if(off < 0 || off >= mem_buf.capacity() || off + payloadLen > mem_buf.capacity()) {
				System.err.println("Buffer bounds error: seqNo=" + seqNo + ", off=" + off + ", payloadLen=" + payloadLen + ", capacity=" + mem_buf.capacity());
				return;
			}
			
			// Eğer bu paket zaten alınmışsa, tekrar yazma
			synchronized(this) {
				if(recv.get(seqNo)) {
					// Zaten alınmış, skip
					return;
				}
				
				// Memory'ye yaz
				try {
					MappedByteBuffer view = mem_buf.duplicate();
					view.position(off);
					view.limit(off + payloadLen);
					
					// Payload'ı temiz bir şekilde kopyala
					ByteBuffer payloadToPut = payload.duplicate();
					payloadToPut.rewind(); // Position'ı 0'a al
					
					view.put(payloadToPut);
					
					// Sadece başarılı yazma sonrası mark et
					recv.set(seqNo);
					
				} catch(Exception e) {
					System.err.println("Memory write error for seq " + seqNo + ": " + e);
					return;
				}
			}
			
			updateCumulativeAck();
		} else {
			// CRC mismatch - bu paketi alınmamış olarak işaretle
			synchronized(this) {
				recv.clear(seqNo);
			}
			// CRC mismatch - sessizce ignore et (network'te bozulmuş paket)
		}
	}

	public long build64(){
		long mask = 0L;
		int base = cum_Ack;
		for(int i = 0; i < 64; i++)
		{
			if(base + i >= total_seq) break;
			if(recv.get(base + i))
					mask |= (1L << i);
		}
		return mask;
	}

	// controlFrames() method removed - use isTransferComplete() instead
	// isTransferComplete() has O(1) complexity vs controlFrames() O(N)
	
	public void printTransferStatus() {
		int received = recv.cardinality();
		int missing = total_seq - received;
		double progress = (received * 100.0) / total_seq;
		
		System.out.printf("Transfer Status: %.2f%% (%d/%d packets, %d missing, cumAck=%d)%n", 
		    progress, received, total_seq, missing, cum_Ack);
	}
	

	public void send_Nack_Frame(){
		long mask = build64();
		frame.fill(fileId, cum_Ack, mask);

		int r;
		int retries = 0;
		final int MAX_RETRIES = 5;
		
		try{
			do{
				r = channel.write(frame.buffer().duplicate());
				if(r == 0) {
					LockSupport.parkNanos(50_000); // Daha kısa bekleme
					retries++;
					if(retries >= MAX_RETRIES) {
						System.err.println("NACK frame send failed after " + MAX_RETRIES + " retries");
						return;
					}
				}
			}while(r == 0 && retries < MAX_RETRIES);
		}catch(IOException e){
			System.err.println("NACK write failed: " + e.getMessage());
			// Don't throw RuntimeException, just logmand continue
		}
	}
	
	ThreadFactory daemonFactory = r -> {
		Thread t = new Thread(r, "nack-scheduler");
		t.setDaemon(true);
		return t;
	};

	public final ScheduledExecutorService scheduler = 
		Executors.newScheduledThreadPool(1, daemonFactory);

	public final Runnable nack_service = () -> {
		try{
			send_Nack_Frame();
		}catch(Exception e){
			System.err.println("Thread Error[nack-scheduler]: " + e); 
		}
	};

	public ScheduledFuture<?> nackHandle;

	public void startNackLoop()
	{
		if(nackHandle == null || nackHandle.isCancelled() || nackHandle.isDone())
		{
		  nackHandle = scheduler.scheduleAtFixedRate(nack_service, 0 , 25, TimeUnit.MILLISECONDS); // Daha hızlı NACK gönderimi
		}
	}
	public void stopNackLoop(){
		if(nackHandle != null){
			nackHandle.cancel(false);
			nackHandle = null;
		}
	}
	
	public void shutdownScheduler(){
		scheduler.shutdown();
	}
	
	public void cleanup() {
		stopNackLoop();
		shutdownScheduler();
		try {
			if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
				scheduler.shutdownNow();
			}
		} catch (InterruptedException e) {
			scheduler.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public void run(){
		if(channel == null)
		{
			throw new IllegalStateException("You must bind the channel first");
		}
		
		try {
			startNackLoop();
			while(!Thread.currentThread().isInterrupted() && !transferCompleted){
				buf.clear();

				int x;
				do{
					try{
					x = channel.read(buf);
					}catch(IOException e){
						System.err.println("read failed: " + e);
						return ;
					}
				if( x == 0 ){
					LockSupport.parkNanos(100_000); // Daha kısa bekleme süresi
					
					if(transferCompleted) {
						System.out.println("Transfer completed, exiting receiver loop.");
						break;
					}
				}
				}while(x == 0 && !transferCompleted);
				
				if(transferCompleted) break;
			
			buf.flip();		

			if (x < CRC32C_HEADER_SIZE || x > TOTAL_PACKET_SIZE || buf.getLong(OFF_FILE_ID) != fileId) {
		    		buf.clear();
		    	continue;
			}

				onData(buf);
				buf.clear();
			}
			
			if(transferCompleted) {
				System.out.println("NackSender: All packets received, transfer complete!");
			}
		} finally {
			cleanup();
		}
	}
	
	public void sendCompletionSignal() {
		try {
			// Özel completion frame gönder
			ByteBuffer completionFrame = ByteBuffer.allocate(8);
			completionFrame.putInt(0xDEADBEEF); // Magic number for completion
			completionFrame.putInt((int)fileId);
			completionFrame.flip();
			
			channel.write(completionFrame);
			System.out.println("✅ Transfer completion signal sent to sender");
		} catch(IOException e) {
			System.err.println("Failed to send completion signal: " + e);
		}
	}
}
