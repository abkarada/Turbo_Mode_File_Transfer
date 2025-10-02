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
	    
	    // Congestion control and threads
	    private CongestionController congestionControl;
	    private Thread statsThread;
	    private Thread nackThread;
	    private Thread retransmissionThread;
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
	        do {
	        	wrote = (int)channel.write(frame);
	        	if(wrote == 0) {
	        		pkt.resetForRetry();
	        		payload.position(0).limit(take);
	        		LockSupport.parkNanos(BACKOFF_NS);
	        	}
	        } while(wrote == 0);
			}catch(IOException e){
				System.err.println("Frame sending error: + " + e);
			}
	    	
	    }
	    
	    public void sendFile(Path filePath, long fileId) throws IOException{
	    	if(channel == null) throw new IllegalStateException("Datagram Channel is null you must bind and connect first");
	    	if(stopRequested) throw new IllegalStateException("Transfer was stopped");
	    	
	    	Thread nackThread = null;
	    	Thread retransmissionThread = null;
	    	
	    	// Socket buffer optimizasyonları
	    	try {
	    		channel.socket().setSendBufferSize(8 * 1024 * 1024); // 8MB send buffer
	    		channel.socket().setReceiveBufferSize(8 * 1024 * 1024); // 8MB receive buffer
	    		System.out.println("📡 Socket buffers set to 8MB each");
	    	} catch(Exception e) {
	    		System.err.println("⚠️  Could not set socket buffers: " + e.getMessage());
	    	}
	    	
	    	try(FileChannel fc = FileChannel.open(filePath, StandardOpenOption.READ)){
	    		long fileSize = fc.size();
	    		if(fileSize > TURBO_MAX) throw new IllegalArgumentException("Turbo Mode is only for  ≤256 MB.");
	    		
	    		MappedByteBuffer mem = fc.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
	    		for(int i = 0; i < MAX_TRY && !mem.isLoaded(); i++) mem.load();
	    		System.out.println("🗂️  Memory-mapped file loaded (" + (fileSize / (1024*1024)) + " MB)");
	    		
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
	    	nackListener.congestionControl = congestionControl; // Congestion control referansını ekle
	    	
	    	// Completion callback ayarla
	    	nackListener.onTransferComplete = () -> {
	    		System.out.println("Sender: Transfer completion detected!");
	    		transferCompleteLatch.countDown();
	    	};
	    	
	    	 this.nackThread = new Thread(() -> {
	    	 	SystemOptimizer.optimizeNetworkThread("nack-listener");
	    	 	nackListener.run();
	    	 }, "nack-listener");
	    	 if (this.nackThread == null) {
	    	 	System.err.println("❌ NackThread creation failed!");
	    	 	return;
	    	 }
	    	 this.nackThread.setDaemon(true);
	    	 this.nackThread.start();
	    	
	    	// Congestion control ekleme
	    	this.congestionControl = new CongestionController();
	    	
	    	// Local network için aggressive mode aktif et
	    	String targetHost = channel.socket().getRemoteSocketAddress().toString();
	    	if (targetHost.contains("127.0.0.1") || targetHost.contains("localhost") || 
	    	    targetHost.contains("192.168.") || targetHost.contains("10.")) {
	    		congestionControl.enableAggressiveMode();
	    	}
	    	
	    	// Statistics display thread with optimization
	    	this.statsThread = new Thread(() -> {
	    		SystemOptimizer.optimizeCurrentThread("stats-display");
	    		while (!Thread.currentThread().isInterrupted()) {
	    			try {
	    				Thread.sleep(2000); // Her 2 saniyede bir stats göster
	    				System.out.println("📊 " + congestionControl.getStats() + " | " + SystemOptimizer.getSystemStatus());
	    			} catch (InterruptedException e) {
	    				break;
	    			}
	    		}
	    	}, "congestion-stats");
	    	if (this.statsThread == null) {
	    		System.err.println("❌ StatsThread creation failed!");
	    		return;
	    	}
	    	this.statsThread.setDaemon(true);
	    	this.statsThread.start();
	    	
	    	// UDT tarzı concurrent transmission - retransmission ve initial transmission aynı anda
	    	FileTransferSender sender = this;
	    	final boolean[] initialTransmissionDone = {false};
	    	
	    	// Retransmission thread - sürekli çalışır
			// Retransmission thread - kendi CRC ve packet instance'ları ile
		this.retransmissionThread = new Thread( () -> {
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
		if (this.retransmissionThread == null) {
			System.err.println("❌ RetransmissionThread creation failed!");
			return;
		}
		this.retransmissionThread.setDaemon(true);
		this.retransmissionThread.start();	
		
		// Batch sender for high-throughput
		BatchSender batchSender = new BatchSender(channel, 64); // 64 packet batches
		
			    	// Ana thread optimizasyonu
	    	SystemOptimizer.optimizeNetworkThread("main-sender");
	    	
		// Initial transmission thread'inde de optimizasyon
		System.out.println("Starting batch-controlled high-speed transmission...");
		int seqNo = 0;
		int batchCount = 0;
		
	    	for(int off = 0; off < mem.capacity(); ){
	    		// Congestion window kontrolü
	    		while (!congestionControl.canSendPacket()) {
	    			LockSupport.parkNanos(1_000); // 1μs bekle
	    		}
	    		
	    		int remaining = mem.capacity() - off;
	    		int take  = Math.min(SLICE_SIZE, remaining);
	    		
	    		// Packet hazırla
	    		ByteBuffer packet = preparePacket(initialCrc, initialPkt, mem, fileId, seqNo, totalSeq, take, off);
	    		
	    		// Batch'e ekle
	    		if (!batchSender.addToBatch(packet)) {
	    			// Batch dolu - gönder
	    			try {
	    				int sent = batchSender.sendBatch();
	    				congestionControl.onPacketSent(); // Batch sent bildirimi  
	    				batchCount++;
	    				
	    				// Rate limiting sadece batch sonunda
	    				if (batchCount % 4 == 0) { // Her 4 batch'te bir
	    					congestionControl.rateLimitSend();
	    				}
	    			} catch(IOException e) {
	    				System.err.println("Batch send error: " + e);
	    			}
	    			
	    			// Şu anki paketi yeni batch'e ekle
	    			batchSender.addToBatch(packet);
	    		}
	                
	                off += take;
	                seqNo++;
	                
	                // Her 500 pakette bir progress göster
	                if (seqNo % 500 == 0) {
	                	double progress = (double)off / mem.capacity() * 100;
	                	System.out.printf("� Progress: %.1f%% (%d/%d packets) - %s | %s\n", 
	                		progress, seqNo, totalSeq, congestionControl.getStats(), batchSender.getStats());
	                }
	    	}
	    	
	    	// Son batch'i gönder
	    	try {
	    		batchSender.flushBatch();
	    		System.out.println("📦 Final batch flushed");
	    	} catch(IOException e) {
	    		System.err.println("Final batch flush error: " + e);
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
	    		
	    		// Stats thread cleanup
	    		if(statsThread != null && statsThread.isAlive()) {
	    			statsThread.interrupt();
	    		}
	    	}
	    }
	    
	    /**
	     * Prepare packet for batching (without sending)
	     */
	    public ByteBuffer preparePacket(CRC32C crc, CRC32C_Packet pkt,
                MappedByteBuffer mem, long fileId,
                int seqNo, int totalSeq, int take, int off) {
	    	
	    	ByteBuffer payload = mem.slice(off, take);
	    	crc.reset();
	    	crc.update(payload.duplicate());
	    	int crc32c = (int) crc.getValue();
	    	
	    	pkt.fillHeader(fileId, seqNo, totalSeq, take, crc32c);
	    	
	    	// Single buffer with header + payload
	    	ByteBuffer combined = ByteBuffer.allocateDirect(22 + take);
	    	combined.put(pkt.headerBuffer().duplicate());
	    	combined.put(payload.position(0).limit(take));
	    	combined.flip();
	    	
	    	return combined;
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
