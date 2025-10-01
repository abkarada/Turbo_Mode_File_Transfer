import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;

public class FileTransferSender {
	    private final DatagramChannel channel;
	    private volatile boolean stopRequested = false;
	    private static final ExecutorService threadPool = 
	        Executors.newCachedThreadPool(r -> {
	            Thread t = new Thread(r);
	            t.setDaemon(true);
	            t.setName("file-transfer-" + t.getId());
	            return t;
	        });

	    public static final long TURBO_MAX  = 256L << 20; // 256 MB
	    public static final int  SLICE_SIZE = 1200;
	    public static final int  MAX_TRY    = 4;
	    public static final int  BACKOFF_NS = 200_000;
	
	    public FileTransferSender(DatagramChannel ch){
		this.channel = ch;
	    }
	    
	    public void requestStop() {
	        this.stopRequested = true;
	    }

		public boolean handshake(long fileId, int file_size, int total_seq) throws IOException {
		if(channel == null) throw new IllegalStateException("Datagram Channel is null you must bind and connect first");
		long candidate_file_Id = -1;
		HandShake_Packet pkt = new HandShake_Packet();
		pkt.make_SYN(fileId, file_size, total_seq);
	
		channel.write(pkt.get_header().duplicate());
		ByteBuffer buffer = ByteBuffer.allocateDirect(HandShake_Packet.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
		
		// Handshake ACK için timeout ekle (5 saniye)
		long ackDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		int r;
		
		do{
			if(System.nanoTime() > ackDeadline) {
				System.err.println("Handshake ACK timeout after 5 seconds");
				return false;
			}
			
			r = channel.read(buffer);
			if(r <= 0) LockSupport.parkNanos(1_000_000); // 1ms bekleme
		}while( r <= 0);
		
		buffer.flip();
		if(r >= HandShake_Packet.HEADER_SIZE && buffer.get(0) == 0x10){
			buffer.position(1); // Position'ı 1'e set et
			candidate_file_Id = buffer.getLong(); // Relative okuma
		}

		if(candidate_file_Id == fileId)
		{
			pkt.make_SYN_ACK(fileId);
			try{
				// SYN_ACK için de timeout ekle
				long synAckDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
				while(channel.write(pkt.get_header().duplicate()) == 0)
				{
					if(System.nanoTime() > synAckDeadline) {
						System.err.println("SYN_ACK send timeout");
						return false;
					}
					pkt.resetForRetransmitter();
					LockSupport.parkNanos(1_000_000); // 1ms bekleme
				}
			}catch(IOException e){
				System.err.println("SYN+ACK Signal Error: " + e);
				return false;
			}
			return true;
		}
		return false;
	}
	    public void sendOne(CRC32C crc, CRC32C_Packet pkt,
                MappedByteBuffer mem, long fileId,
                int seqNo, int totalSeq, int take, int off) throws IOException{
	    	
	    	ByteBuffer payload = mem.slice(off, take);
	    	crc.reset();
	    	crc.update(payload.duplicate());
	    	int crc32c = (int) crc.getValue();
	    	
	    	pkt.fillHeader(fileId, seqNo, totalSeq, take, crc32c);
	    	
	        ByteBuffer[] frame = new ByteBuffer[]{ pkt.headerBuffer(), payload.position(0).limit(take) };
		
	        int wrote;
			try{
	        wrote = (int)channel.write(frame);
	        if(wrote == 0) {
	        	// Buffer dolu - hemen geri dön, retry yok
	        	throw new IOException("Send buffer full");
	        }
			}catch(IOException e){
				System.err.println("Frame sending error: + " + e);
			}
	    	
	    }
	    
	    // Adaptive batch sending with congestion control
	    private static final int MIN_BATCH_SIZE = 4;   // Minimum batch size
	    private static final int MAX_BATCH_SIZE = 16;  // Maximum batch size  
	    private volatile int currentBatchSize = MIN_BATCH_SIZE; // Adaptive batch size
	    private volatile int inflightPackets = 0;      // In-flight paket sayısı
	    private static final int MAX_INFLIGHT = 64;    // Maximum in-flight packets
	    
	    public void sendBatch(CRC32C crc, CRC32C_Packet pkt, MappedByteBuffer mem, 
	                         long fileId, int startSeq, int totalSeq, int batchCount) throws IOException {
	        
	        // Batch için buffer array'i hazırla
	        ByteBuffer[] batchBuffers = new ByteBuffer[batchCount * 2]; // Her paket için header + payload
	        
	        int bufferIndex = 0;
	        for(int i = 0; i < batchCount; i++) {
	            int seqNo = startSeq + i;
	            int off = seqNo * SLICE_SIZE;
	            
	            if(off >= mem.capacity()) break; // Dosya sonu
	            
	            int remaining = mem.capacity() - off;
	            int take = Math.min(SLICE_SIZE, remaining);
	            
	            // Payload slice
	            ByteBuffer payload = mem.slice(off, take);
	            
	            // CRC hesapla
	            crc.reset();
	            crc.update(payload.duplicate());
	            int crc32c = (int) crc.getValue();
	            
	            // Header hazırla
	            pkt.fillHeader(fileId, seqNo, totalSeq, take, crc32c);
	            
	            // Batch array'e ekle
	            batchBuffers[bufferIndex++] = pkt.headerBuffer().duplicate();
	            batchBuffers[bufferIndex++] = payload.duplicate();
	        }
	        
	        // Batch'i tek seferde gönder (gathering write)
	        long totalWritten = 0;
	        int attempts = 0;
	        do {
	            long written = channel.write(batchBuffers, 0, bufferIndex);
	            totalWritten += written;
	            attempts++;
	            
	            if(written == 0 && attempts < 3) {
	                LockSupport.parkNanos(1_000); // 1μs minimal backoff
	            }
	        } while(totalWritten == 0 && attempts < 3);
	    }
	    
	    public void sendFile(Path filePath, long fileId) throws IOException{
	    	if(channel == null) throw new IllegalStateException("Datagram Channel is null you must bind and connect first");
	    	if(stopRequested) throw new IllegalStateException("Transfer was stopped");
	    	
	    	Thread nackThread = null;
	    	Thread retransmissionThread = null;
	    	
	    	try(FileChannel fc = FileChannel.open(filePath, StandardOpenOption.READ)){
	    		long fileSize = fc.size();
	    		if(fileSize > TURBO_MAX) throw new IllegalArgumentException("Turbo Mode is only for  ≤256 MB.");
	    		
	    		MappedByteBuffer mem = fc.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
	    		for(int i = 0; i < MAX_TRY && !mem.isLoaded(); i++) mem.load();
	    		
	    		int totalSeq = (int) ((fileSize + SLICE_SIZE - 1) / SLICE_SIZE);
	    		
	    		// Thread-safe için her thread kendi instance'larını kullanacak
	    		CRC32C initialCrc = new CRC32C();
	    		CRC32C_Packet initialPkt = new CRC32C_Packet();
	    		
			long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
			final long MAX_BACKOFF = 10_000_000L;
			long backoff  = 1_000_000L;
			boolean hand_shaking;
			do{
				hand_shaking = handshake(fileId, (int) fileSize, totalSeq);
				if(hand_shaking) break;

				if(Thread.currentThread().isInterrupted()){
					throw new IllegalStateException("Handshake Thread interrupted");
				}
				if(System.nanoTime() > deadline){
					throw new IllegalStateException("Handshake timeout");
				}
				LockSupport.parkNanos(backoff);
				 if (backoff < MAX_BACKOFF) {
					   backoff = Math.min(MAX_BACKOFF, backoff << 1);
					}
			}while(!hand_shaking);

	    	ConcurrentLinkedQueue<Integer> retxQueue = new ConcurrentLinkedQueue<>();
	    	
	    	// Transfer completion için latch
	    	final CountDownLatch transferCompleteLatch = new CountDownLatch(1);
	    	 
	    	// NACK listener'ı başlat
	    	NackListener nackListener = new NackListener(channel, fileId, totalSeq, retxQueue, BACKOFF_NS);
	    	
	    	// Completion callback ayarla
	    	nackListener.onTransferComplete = () -> {
	    		System.out.println("Sender: Transfer completion detected!");
	    		transferCompleteLatch.countDown();
	    	};
	    	
	    	 nackThread = new Thread(nackListener, "nack-listener");
	    	 nackThread.setDaemon(true);
	    	 nackThread.start();
	    	
	    	// UDT tarzı concurrent transmission - retransmission ve initial transmission aynı anda
	    	FileTransferSender sender = this;
	    	final boolean[] initialTransmissionDone = {false};
	    	
	    	// Retransmission thread - sürekli çalışır
			// Retransmission thread - kendi CRC ve packet instance'ları ile
		retransmissionThread = new Thread( () -> {
				CRC32C retxCrc = new CRC32C();
				CRC32C_Packet retxPkt = new CRC32C_Packet();
				
				while(!Thread.currentThread().isInterrupted() && !stopRequested){
	    			Integer miss = retxQueue.poll();
	    			if(miss == null) {
	    				// Eğer initial transmission bitti ve queue boşsa, biraz bekle
	    				if(initialTransmissionDone[0]) {
	    					LockSupport.parkNanos(1_000_000); // 1ms bekle
	    					continue;
	    				}
	    				LockSupport.parkNanos(50_000); // 50μs hızlı polling
	    				continue;
	    			}
    			
    			if(miss < 0 || miss >= totalSeq) {
    				System.err.println("Invalid sequence number: " + miss);
    				continue;
    			}
    			
    			int off = miss*SLICE_SIZE;
    			int take = Math.min(SLICE_SIZE, mem.capacity() - off);
    			if(take > 0) {
					try{
    				sender.sendOne(retxCrc, retxPkt, mem, fileId, miss, totalSeq, take, off);
    				}catch(IOException e){
						System.err.println("Retransmission error for seq " + miss + ": " + e);
					}}
    			}
	}, "retransmission-thread");
		retransmissionThread.setDaemon(true);
		retransmissionThread.start();	
		
		// Initial transmission - rate-limited single packets (congestion aware)
		System.out.println("Starting initial transmission with congestion control...");
		int seqNo = 0;
		long packetInterval = 10_000; // 10μs başlangıç interval
		long lastSendTime = System.nanoTime();
		
		for(int off = 0; off < mem.capacity(); ){
			// In-flight packet limit kontrolü
			while(inflightPackets >= MAX_INFLIGHT) {
				LockSupport.parkNanos(1_000_000); // 1ms bekle
			}
			
			int remaining = mem.capacity() - off;
			int take = Math.min(SLICE_SIZE, remaining);
			
			try {
				// Rate limiting - paketler arası minimum interval
				long now = System.nanoTime();
				long elapsed = now - lastSendTime;
				if(elapsed < packetInterval) {
					LockSupport.parkNanos(packetInterval - elapsed);
				}
				
				sendOne(initialCrc, initialPkt, mem, fileId, seqNo, totalSeq, take, off);
				inflightPackets++;
				lastSendTime = System.nanoTime();
				
				// Adaptive rate adjustment
				if(seqNo % 100 == 0) { // Her 100 pakette bir ayarla
					if(inflightPackets < MAX_INFLIGHT / 2) {
						packetInterval = Math.max(1_000, packetInterval - 1_000); // Hızlandır
					} else if(inflightPackets > MAX_INFLIGHT * 3/4) {
						packetInterval = Math.min(100_000, packetInterval + 2_000); // Yavaşlat
					}
				}
				
			} catch(IOException e) {
				System.err.println("Send error at seq " + seqNo + ": " + e);
				packetInterval = Math.min(100_000, packetInterval * 2); // Hata durumunda yavaşlat
			}
			
			off += take;
			seqNo++;
		}
	    	
	    	initialTransmissionDone[0] = true;
	    	System.out.println("Initial transmission completed, retransmissions continue...");
	    	
	    	// Receiver'dan completion sinyali bekle - timeout yok, gerçek completion
	    	try {
	    		boolean completed = transferCompleteLatch.await(300, TimeUnit.SECONDS); // Maksimum 5 dakika güvenlik
	    		if(completed) {
	    			System.out.println("File transfer completed successfully!");
	    		} else {
	    			System.err.println("Transfer timeout - very large file or network issue");
	    		}
	    	} catch(InterruptedException e) {
	    		System.err.println("Transfer interrupted");
	    		Thread.currentThread().interrupt();
	    	}
	    	}finally {
	    		// Thread cleanup
	    		if(nackThread != null && nackThread.isAlive()) {
	    			nackThread.interrupt();
	    			try {
	    				nackThread.join(1000); 
	    			} catch (InterruptedException e) {
	    				Thread.currentThread().interrupt();
	    			}
	    		}
	    		
	    		if(retransmissionThread != null && retransmissionThread.isAlive()) {
	    			retransmissionThread.interrupt();
	    			try {
	    				retransmissionThread.join(1000); 
	    			} catch (InterruptedException e) {
	    				Thread.currentThread().interrupt();
	    			}
	    		}
	    	}
	    }
	    
	    public static void shutdownThreadPool() {
	        threadPool.shutdown();
	        try {
	            if (!threadPool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
	                threadPool.shutdownNow();
	            }
	        } catch (InterruptedException e) {
	            threadPool.shutdownNow();
	            Thread.currentThread().interrupt();
	        }
	    }
	}
