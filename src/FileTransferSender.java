import java.io.IOException;import java.io.IOException;

import java.nio.ByteBuffer;import java.nio.ByteBuffer;

import java.nio.ByteOrder;import java.nio.ByteOrder;

import java.nio.MappedByteBuffer;import java.nio.MappedByteBuffer;

import java.nio.channels.DatagramChannel;import java.nio.channels.DatagramChannel;

import java.nio.channels.FileChannel;import java.nio.channels.FileChannel;

import java.nio.file.Path;import java.nio.file.Path;

import java.nio.file.StandardOpenOption;import java.nio.file.StandardOpenOption;

import java.util.concurrent.ConcurrentLinkedQueue;import java.util.concurrent.Concurre	                // Her 200 pakette progress

import java.util.concurrent.ExecutorService;	                if (seqNo % 200 == 0) {

import java.util.concurrent.Executors;	                	double progress = (double)off / mem.capacity() * 100;

import java.util.concurrent.locks.LockSupport;	                	System.out.printf("📤 Progress: %.1f%% (%d/%d) - %s\n", 

import java.util.concurrent.CountDownLatch;	                		progress, seqNo, totalSeq, congestionControl.getStats());

import java.util.concurrent.TimeUnit;	                }

import java.util.zip.CRC32C;	    	}import java.util.concurrent.ExecutorService;

import java.util.concurrent.Executors;

public class FileTransferSender {import java.util.concurrent.locks.LockSupport;

    private final DatagramChannel channel;import java.util.concurrent.CountDownLatch;

    private volatile boolean stopRequested = false;import java.util.concurrent.TimeUnit;

    import java.util.zip.CRC32C;

    // Threads

    private Thread nackThread;public class FileTransferSender {

    private Thread retransmissionThread;	    private final DatagramChannel channel;

    	    private volatile boolean stopRequested = false;

    private static final ExecutorService threadPool = 	    

        Executors.newCachedThreadPool(r -> {	    // Congestion control and threads

            Thread t = new Thread(r);	    private CongestionController congestionControl;

            t.setDaemon(true);	    private Thread statsThread;

            t.setName("file-transfer-" + t.getId());	    private Thread nackThread;

            return t;	    private Thread retransmissionThread;

        });	    private static final ExecutorService threadPool = 

	        Executors.newCachedThreadPool(r -> {

    public static final long TURBO_MAX  = 256L << 20; // 256 MB	            Thread t = new Thread(r);

    public static final int  SLICE_SIZE = 1200;	            t.setDaemon(true);

    public static final int  MAX_TRY    = 4;	            t.setName("file-transfer-" + t.getId());

    public static final int  BACKOFF_NS = 50_000; // 50μs	            return t;

	        });

    public FileTransferSender(DatagramChannel channel) {

        this.channel = channel;	    public static final long TURBO_MAX  = 256L << 20; // 256 MB

    }	    public static final int  SLICE_SIZE = 1200;

    	    public static final int  MAX_TRY    = 4;

    public void requestStop() {	    public static final int  BACKOFF_NS = 200_000;

        this.stopRequested = true;	

    }	    public FileTransferSender(DatagramChannel ch){

		this.channel = ch;

    public boolean handshake(long fileId, int file_size, int total_seq) throws IOException {	    }

        if(channel == null) throw new IllegalStateException("Datagram Channel is null you must bind and connect first");	    

        long candidate_file_Id = -1;	    public void requestStop() {

        HandShake_Packet pkt = new HandShake_Packet();	        this.stopRequested = true;

        pkt.make_SYN(fileId, file_size, total_seq);	    }



        channel.write(pkt.get_header().duplicate());		public boolean handshake(long fileId, int file_size, int total_seq) throws IOException {

        ByteBuffer buffer = ByteBuffer.allocateDirect(HandShake_Packet.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);		if(channel == null) throw new IllegalStateException("Datagram Channel is null you must bind and connect first");

        		long candidate_file_Id = -1;

        // Handshake ACK için timeout ekle (5 saniye)		HandShake_Packet pkt = new HandShake_Packet();

        long ackDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);		pkt.make_SYN(fileId, file_size, total_seq);

        int r;	

        		channel.write(pkt.get_header().duplicate());

        do{		ByteBuffer buffer = ByteBuffer.allocateDirect(HandShake_Packet.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);

            if(System.nanoTime() > ackDeadline) {		

                System.err.println("Handshake ACK timeout");		// Handshake ACK için timeout ekle (5 saniye)

                return false;		long ackDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

            }		int r;

            r = channel.read(buffer);		

            if(r <= 0) LockSupport.parkNanos(1_000_000); // 1ms bekleme		do{

        }while( r <= 0);			if(System.nanoTime() > ackDeadline) {

        				System.err.println("Handshake ACK timeout after 5 seconds");

        buffer.flip();				return false;

        if(r >= HandShake_Packet.HEADER_SIZE && buffer.get(0) == 0x10){			}

            buffer.position(1); // Position'ı 1'e set et			

            candidate_file_Id = buffer.getLong(); // Relative okuma			r = channel.read(buffer);

        }			if(r <= 0) LockSupport.parkNanos(1_000_000); // 1ms bekleme

		}while( r <= 0);

        if(candidate_file_Id == fileId) {		

            pkt.make_SYN_ACK(fileId);		buffer.flip();

            try{		if(r >= HandShake_Packet.HEADER_SIZE && buffer.get(0) == 0x10){

                // SYN_ACK için de timeout ekle			buffer.position(1); // Position'ı 1'e set et

                long synAckDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);			candidate_file_Id = buffer.getLong(); // Relative okuma

                while(channel.write(pkt.get_header().duplicate()) == 0) {		}

                    if(System.nanoTime() > synAckDeadline) {

                        System.err.println("SYN_ACK send timeout");		if(candidate_file_Id == fileId)

                        return false;		{

                    }			pkt.make_SYN_ACK(fileId);

                    pkt.resetForRetransmitter();			try{

                    LockSupport.parkNanos(1_000_000); // 1ms bekleme				// SYN_ACK için de timeout ekle

                }				long synAckDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);

            }catch(IOException e){				while(channel.write(pkt.get_header().duplicate()) == 0)

                System.err.println("SYN+ACK Signal Error: " + e);				{

                return false;					if(System.nanoTime() > synAckDeadline) {

            }						System.err.println("SYN_ACK send timeout");

            return true;						return false;

        }					}

        return false;					pkt.resetForRetransmitter();

    }					LockSupport.parkNanos(1_000_000); // 1ms bekleme

    				}

    public void sendOne(CRC32C crc, CRC32C_Packet pkt,			}catch(IOException e){

                MappedByteBuffer mem, long fileId,				System.err.println("SYN+ACK Signal Error: " + e);

                int seqNo, int totalSeq, int take, int off) throws IOException{				return false;

        			}

        ByteBuffer payload = mem.slice(off, take);			return true;

        crc.reset();		}

        crc.update(payload.duplicate());		return false;

        int crc32c = (int) crc.getValue();	}

        	    public void sendOne(CRC32C crc, CRC32C_Packet pkt,

        pkt.fillHeader(fileId, seqNo, totalSeq, take, crc32c);                MappedByteBuffer mem, long fileId,

                        int seqNo, int totalSeq, int take, int off) throws IOException{

        ByteBuffer[] frame = new ByteBuffer[]{ pkt.headerBuffer(), payload.position(0).limit(take) };	    	

	    	ByteBuffer payload = mem.slice(off, take);

        int wrote;	    	crc.reset();

        try{	    	crc.update(payload.duplicate());

        do {	    	int crc32c = (int) crc.getValue();

            wrote = (int) channel.write(frame);	    	

            if(wrote == 0) LockSupport.parkNanos(1_000); // 1μs	    	pkt.fillHeader(fileId, seqNo, totalSeq, take, crc32c);

        } while(wrote == 0);	    	

        }catch(IOException e){	        ByteBuffer[] frame = new ByteBuffer[]{ pkt.headerBuffer(), payload.position(0).limit(take) };

            throw new IOException("Write error for seq " + seqNo + ": " + e.getMessage(), e);		

        }	        int wrote;

    }			try{

    	        do {

    public void sendFile(Path filePath) throws IOException {	        	wrote = (int)channel.write(frame);

        if(stopRequested) throw new IllegalStateException("Transfer was stopped");	        	if(wrote == 0) {

        	        		pkt.resetForRetry();

        Thread nackThread = null;	        		payload.position(0).limit(take);

        Thread retransmissionThread = null;	        		LockSupport.parkNanos(BACKOFF_NS);

        	        	}

        // Socket buffer optimization - Simple and effective	        } while(wrote == 0);

        try {			}catch(IOException e){

            channel.socket().setSendBufferSize(2 * 1024 * 1024); // 2MB send buffer				System.err.println("Frame sending error: + " + e);

            channel.socket().setReceiveBufferSize(1 * 1024 * 1024); // 1MB receive buffer			}

            System.out.println("📡 Socket buffers optimized");	    	

        } catch(Exception e) {	    }

            System.err.println("⚠️  Could not set socket buffers: " + e.getMessage());	    

        }	    public void sendFile(Path filePath, long fileId) throws IOException{

        	    	if(channel == null) throw new IllegalStateException("Datagram Channel is null you must bind and connect first");

        try(FileChannel fc = FileChannel.open(filePath, StandardOpenOption.READ)){	    	if(stopRequested) throw new IllegalStateException("Transfer was stopped");

            long fileSize = fc.size();	    	

            if(fileSize > TURBO_MAX) throw new IllegalArgumentException("Turbo Mode is only for  ≤256 MB.");	    	Thread nackThread = null;

            	    	Thread retransmissionThread = null;

            MappedByteBuffer mem = fc.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);	    	

            for(int i = 0; i < MAX_TRY && !mem.isLoaded(); i++) mem.load();	    	// Socket buffer optimizasyonları - basit ve etkili

            System.out.println("🗂️  Memory-mapped file loaded (" + (fileSize / (1024*1024)) + " MB)");	    	try {

            	    		channel.socket().setSendBufferSize(2 * 1024 * 1024); // 2MB send buffer

            int totalSeq = (int) ((fileSize + SLICE_SIZE - 1) / SLICE_SIZE);	    		channel.socket().setReceiveBufferSize(1 * 1024 * 1024); // 1MB receive buffer

            	    		System.out.println("📡 Socket buffers optimized");

            // Thread-safe için her thread kendi instance'larını kullanacak	    	} catch(Exception e) {

            CRC32C initialCrc = new CRC32C();	    		System.err.println("⚠️  Could not set socket buffers: " + e.getMessage());

            CRC32C_Packet initialPkt = new CRC32C_Packet();	    	}

            	    	

            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);	    	try(FileChannel fc = FileChannel.open(filePath, StandardOpenOption.READ)){

            final long MAX_BACKOFF = 10_000_000L;	    		long fileSize = fc.size();

            long backoff  = 1_000_000L;	    		if(fileSize > TURBO_MAX) throw new IllegalArgumentException("Turbo Mode is only for  ≤256 MB.");

            	    		

            long fileId = System.nanoTime() % 1000000000L + System.currentTimeMillis() % 1000000L;	    		MappedByteBuffer mem = fc.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);

            	    		for(int i = 0; i < MAX_TRY && !mem.isLoaded(); i++) mem.load();

            boolean handshakeSuccess = false;	    		System.out.println("🗂️  Memory-mapped file loaded (" + (fileSize / (1024*1024)) + " MB)");

            while(!handshakeSuccess && System.nanoTime() < deadline && !stopRequested) {	    		

                try {	    		int totalSeq = (int) ((fileSize + SLICE_SIZE - 1) / SLICE_SIZE);

                    handshakeSuccess = handshake(fileId, (int)fileSize, totalSeq);	    		

                    if(!handshakeSuccess) {	    		// Thread-safe için her thread kendi instance'larını kullanacak

                        System.out.println("Handshake failed, retrying in " + (backoff/1_000_000) + "ms...");	    		CRC32C initialCrc = new CRC32C();

                        LockSupport.parkNanos(backoff);	    		CRC32C_Packet initialPkt = new CRC32C_Packet();

                        backoff = Math.min(backoff * 2, MAX_BACKOFF);	    		

                    }			long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);

                } catch(IOException e) {			final long MAX_BACKOFF = 10_000_000L;

                    System.err.println("Handshake error: " + e.getMessage());			long backoff  = 1_000_000L;

                    LockSupport.parkNanos(backoff);			boolean hand_shaking;

                    backoff = Math.min(backoff * 2, MAX_BACKOFF);			do{

                }				hand_shaking = handshake(fileId, (int) fileSize, totalSeq);

            }				if(hand_shaking) break;

            

            if(!handshakeSuccess) {				if(Thread.currentThread().isInterrupted()){

                throw new IOException("Handshake failed after multiple attempts");					throw new IllegalStateException("Handshake Thread interrupted");

            }				}

            				if(System.nanoTime() > deadline){

            System.out.println("Handshake successful, starting file transfer...");					throw new IllegalStateException("Handshake timeout");

            				}

            // NACK handling için concurrent queue				LockSupport.parkNanos(backoff);

            final ConcurrentLinkedQueue<Integer> retxQueue = new ConcurrentLinkedQueue<>();				 if (backoff < MAX_BACKOFF) {

            final CountDownLatch transferCompleteLatch = new CountDownLatch(1);					   backoff = Math.min(MAX_BACKOFF, backoff << 1);

             					}

            // NACK listener'ı başlat - Simple version			}while(!hand_shaking);

            NackListener nackListener = new NackListener(channel, fileId, totalSeq, retxQueue, BACKOFF_NS);

            	    	ConcurrentLinkedQueue<Integer> retxQueue = new ConcurrentLinkedQueue<>();

            // Completion callback ayarla	    	

            nackListener.onTransferComplete = () -> {	    	// Transfer completion için latch

                System.out.println("Sender: Transfer completion detected!");	    	final CountDownLatch transferCompleteLatch = new CountDownLatch(1);

                transferCompleteLatch.countDown();	    	 

            };	    	// NACK listener'ı başlat

             	    	NackListener nackListener = new NackListener(channel, fileId, totalSeq, retxQueue, BACKOFF_NS);

            this.nackThread = new Thread(nackListener, "nack-listener");	    	nackListener.congestionControl = congestionControl; // Congestion control referansını ekle

            if (this.nackThread == null) {	    	

                System.err.println("❌ NackThread creation failed!");	    	// Completion callback ayarla

                return;	    	nackListener.onTransferComplete = () -> {

            }	    		System.out.println("Sender: Transfer completion detected!");

            this.nackThread.setDaemon(true);	    		transferCompleteLatch.countDown();

            this.nackThread.start();	    	};

            	    	

            // Retransmission thread - Simple version	    	 this.nackThread = new Thread(() -> {

            this.retransmissionThread = new Thread( () -> {	    	 	SystemOptimizer.optimizeNetworkThread("nack-listener");

                CRC32C retxCrc = new CRC32C();	    	 	nackListener.run();

                CRC32C_Packet retxPkt = new CRC32C_Packet();	    	 }, "nack-listener");

                	    	 if (this.nackThread == null) {

                while(!Thread.currentThread().isInterrupted() && !stopRequested){	    	 	System.err.println("❌ NackThread creation failed!");

                    Integer miss = retxQueue.poll();	    	 	return;

                    if(miss == null) {	    	 }

                        LockSupport.parkNanos(100_000); // 100μs bekle	    	 this.nackThread.setDaemon(true);

                        continue;	    	 this.nackThread.start();

                    }	    	

                	    	// Congestion control ekleme

                    if(miss < 0 || miss >= totalSeq) {	    	this.congestionControl = new CongestionController();

                        System.err.println("Invalid sequence number: " + miss);	    	

                        continue;	    	// Local network için aggressive mode aktif et

                    }	    	String targetHost = channel.socket().getRemoteSocketAddress().toString();

                	    	if (targetHost.contains("127.0.0.1") || targetHost.contains("localhost") || 

                    int off = miss*SLICE_SIZE;	    	    targetHost.contains("192.168.") || targetHost.contains("10.")) {

                    int take = Math.min(SLICE_SIZE, mem.capacity() - off);	    		congestionControl.enableAggressiveMode();

                    if(take > 0) {	    	}

                        try{	    	

                            sendOne(retxCrc, retxPkt, mem, fileId, miss, totalSeq, take, off);	    	// Statistics display thread with optimization

                        }catch(IOException e){	    	this.statsThread = new Thread(() -> {

                            System.err.println("Retransmission error for seq " + miss + ": " + e);	    		SystemOptimizer.optimizeCurrentThread("stats-display");

                        }	    		while (!Thread.currentThread().isInterrupted()) {

                    }	    			try {

                }	    				Thread.sleep(2000); // Her 2 saniyede bir stats göster

            }, "retransmission-thread");	    				System.out.println("📊 " + congestionControl.getStats() + " | " + SystemOptimizer.getSystemStatus());

            	    			} catch (InterruptedException e) {

            if (this.retransmissionThread == null) {	    				break;

                System.err.println("❌ RetransmissionThread creation failed!");	    			}

                return;	    		}

            }	    	}, "congestion-stats");

            this.retransmissionThread.setDaemon(true);	    	if (this.statsThread == null) {

            this.retransmissionThread.start();    	    		System.err.println("❌ StatsThread creation failed!");

            	    		return;

            // Simple high-speed transmission - NO COMPLEXITY!	    	}

            System.out.println("Starting optimized transmission...");	    	this.statsThread.setDaemon(true);

            int seqNo = 0;	    	this.statsThread.start();

            	    	

            for(int off = 0; off < mem.capacity(); ){	    	// UDT tarzı concurrent transmission - retransmission ve initial transmission aynı anda

                int remaining = mem.capacity() - off;	    	FileTransferSender sender = this;

                int take  = Math.min(SLICE_SIZE, remaining);	    	final boolean[] initialTransmissionDone = {false};

                	    	

                // Direct send - no batching, no complex congestion control	    	// Retransmission thread - sürekli çalışır

                sendOne(initialCrc, initialPkt, mem, fileId, seqNo, totalSeq, take, off);			// Retransmission thread - kendi CRC ve packet instance'ları ile

                		this.retransmissionThread = new Thread( () -> {

                // Very light rate limiting - only every 64 packets				CRC32C retxCrc = new CRC32C();

                if (seqNo % 64 == 0) {				CRC32C_Packet retxPkt = new CRC32C_Packet();

                    LockSupport.parkNanos(5_000); // 5μs pause				

                }				while(!Thread.currentThread().isInterrupted() && !stopRequested){

                	    			Integer miss = retxQueue.poll();

                off += take;	    			if(miss == null) {

                seqNo++;	    				// Eğer initial transmission bitti ve queue boşsa, biraz bekle

                	    				if(initialTransmissionDone[0]) {

                // Progress every 200 packets	    					LockSupport.parkNanos(1_000_000); // 1ms bekle

                if (seqNo % 200 == 0) {	    					continue;

                    double progress = (double)off / mem.capacity() * 100;	    				}

                    System.out.printf("📤 Progress: %.1f%% (%d/%d packets)\n", 	    				LockSupport.parkNanos(50_000); // 50μs hızlı polling

                        progress, seqNo, totalSeq);	    				continue;

                }	    			}

            }    			

                			if(miss < 0 || miss >= totalSeq) {

            System.out.println("Initial transmission completed, waiting for completion...");    				System.err.println("Invalid sequence number: " + miss);

                				continue;

            // Receiver'dan completion sinyali bekle - timeout ile    			}

            try {    			

                boolean completed = transferCompleteLatch.await(300, TimeUnit.SECONDS);    			int off = miss*SLICE_SIZE;

                if(completed) {    			int take = Math.min(SLICE_SIZE, mem.capacity() - off);

                    System.out.println("File transfer completed successfully!");    			if(take > 0) {

                } else {					try{

                    System.err.println("Transfer timeout - very large file or network issue");    				sender.sendOne(retxCrc, retxPkt, mem, fileId, miss, totalSeq, take, off);

                }    				}catch(IOException e){

            } catch(InterruptedException e) {						System.err.println("Retransmission error for seq " + miss + ": " + e);

                System.err.println("Transfer interrupted");					}}

                Thread.currentThread().interrupt();    			}

            }	}, "retransmission-thread");

        } finally {		if (this.retransmissionThread == null) {

            // Thread cleanup			System.err.println("❌ RetransmissionThread creation failed!");

            if(nackThread != null && nackThread.isAlive()) {			return;

                nackThread.interrupt();		}

                try {		this.retransmissionThread.setDaemon(true);

                    nackThread.join(1000); 		this.retransmissionThread.start();	

                } catch (InterruptedException e) {		

                    Thread.currentThread().interrupt();		// Basit ve hızlı transmission - NO BATCH COMPLEXITY!

                }		System.out.println("Starting simple high-speed transmission...");

            }		int seqNo = 0;

            		

            if(retransmissionThread != null && retransmissionThread.isAlive()) {	    	for(int off = 0; off < mem.capacity(); ){

                retransmissionThread.interrupt();	    		// Hafif congestion window kontrolü

                try {	    		while (!congestionControl.canSendPacket()) {

                    retransmissionThread.join(1000); 	    			LockSupport.parkNanos(5_000); // 5μs bekle

                } catch (InterruptedException e) {	    		}

                    Thread.currentThread().interrupt();	    		

                }	    		int remaining = mem.capacity() - off;

            }	    		int take  = Math.min(SLICE_SIZE, remaining);

        }	    		

    }	    		// Direkt gönder - NO BATCH!

    	                sendOne(initialCrc, initialPkt, mem, fileId, seqNo, totalSeq, take, off);

    public static void shutdownThreadPool() {	                congestionControl.onPacketSent();

        threadPool.shutdown();	                

        try {	                // Minimal rate control

            if (!threadPool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {	                if (seqNo % 32 == 0) { // Her 32 pakette hafif pause

                threadPool.shutdownNow();	                	LockSupport.parkNanos(2_000); // 2μs

            }	                }

        } catch (InterruptedException e) {	                

            threadPool.shutdownNow();	                off += take;

            Thread.currentThread().interrupt();	                seqNo++;

        }	                

    }	                // Her 500 pakette bir progress göster

}	                if (seqNo % 500 == 0) {
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
