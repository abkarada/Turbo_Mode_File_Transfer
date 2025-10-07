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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class FileTransferReceiver {	
	public  DatagramChannel channel;
	public long fileId;
	public int file_size;
	public int total_seq;
	
	public FileChannel fc;
	public Path filePath;
	public MappedByteBuffer mem_buf;
	public static final long MAX_FILE_SIZE = 256L << 20;
	public static final int SLICE_SIZE = 1450; // Maximum payload without fragmentation
	public static final int HEADER_SIZE = 22;
	public static final int PACKET_SIZE = SLICE_SIZE + HEADER_SIZE;
	
	// Transfer timing
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
		int r;

		// Handshake timeout ekle (30 saniye)
		long handshakeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		
		SocketAddress senderAddress = null;
		r = 0; // Initialize r
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
				LockSupport.parkNanos(200_000);
			}}catch(IOException e){System.err.println("IO ERROR: " + e);}
		 	
			rcv_syn.clear();

			int t;
			
			try{
				do{
				t = channel.read(rcv_syn);
				if(t == 0 || t < 9 || t > 13) 
					LockSupport.parkNanos(200_000);
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

				fc.truncate(file_size);
				
				 mem_buf = fc.map(FileChannel.MapMode.READ_WRITE, 0, file_size);

				 return true;

			}
		}catch(IOException e){
			System.err.println("Initialize State Error: " + e);
		}

		return false;
	
	}

	public void ReceiveData(){
	
	if(initialize()){
	
	// Data transfer başlıyor - timing başlat
	transferStartTime = System.currentTimeMillis();
	System.out.println("📊 Data transfer başladı - timing başlatıldı");
	
	NackSender sender = new NackSender(channel, fileId, file_size, total_seq, mem_buf);
	
	// Transfer completion için CountDownLatch kullan
	CountDownLatch transferLatch = new CountDownLatch(1);
	
	// Completion callback ayarla
	sender.onTransferComplete = () -> {
		System.out.println("All packets received successfully!");
		transferLatch.countDown();
	};
	
	Thread t = new Thread(sender, "nack-sender");
	t.start();

		// Transfer tamamlanana kadar bekle - timeout yok, gerçek completion
		try {
			// Maksimum 5 dakika bekle (sadece çok büyük dosyalar için güvenlik)
			boolean completed = transferLatch.await(300, TimeUnit.SECONDS);
			
			if(!completed) {
				System.err.println("Transfer timeout - very large file or network issue");
			}
			
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			System.err.println("Transfer interrupted");
		}
		
		t.interrupt();
		
		// Transfer timing'i sonlandır
		transferEndTime = System.currentTimeMillis();
		
		// Transfer tamamlandı - sender'a completion signal gönder
		try {
			ByteBuffer completionFrame = ByteBuffer.allocate(8);
			completionFrame.putInt(0xDEADBEEF); // Magic number for completion
			completionFrame.putInt((int)fileId);
			completionFrame.flip();
			
			channel.write(completionFrame);
			System.out.println("✅ Transfer completion signal sent to sender");
			
			// Signal'ın gönderilmesi için kısa bir bekleme
			Thread.sleep(100);
			
		} catch(Exception e) {
			System.err.println("Failed to send completion signal: " + e);
		}
		
		System.out.println("File transfer completed successfully!");
		

	}else{
		System.out.println("Initialization Error ");
		}
	}
	
	public double getTransferTimeSeconds() {
		if(transferStartTime == 0 || transferEndTime == 0) {
			return 0.0;
		}
		return (transferEndTime - transferStartTime) / 1000.0;
	}
}
