import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

public class NackListener implements Runnable{
	public final DatagramChannel channel;
	public final long fileId;
	public final int totalSeq;
	public final ConcurrentLinkedQueue<Integer> retxQueue;
	public final int backoffNs;
	
	// Completion callback
	public volatile Runnable onTransferComplete = null;
	
	// Congestion control reference
	public volatile CongestionController congestionControl = null;
	
    public static final int DEFAULT_BACKOFF_NS = 200_000;

	public NackListener(DatagramChannel channel,
			long fileId,
			int totalSeq,
			ConcurrentLinkedQueue<Integer> retxQueue,
			int backoffNs){
        this.channel   = channel;
        this.fileId    = fileId;
        this.totalSeq  = totalSeq;
        this.retxQueue = retxQueue;
        this.backoffNs = backoffNs > 0 ? backoffNs : DEFAULT_BACKOFF_NS;
	}
	
	@Override
	public void run() {
		final ByteBuffer ctrl = ByteBuffer.allocateDirect(Math.max(NackFrame.SIZE, 8)); // Completion signal için 8 byte
		while(!Thread.currentThread().isInterrupted()) {
			ctrl.clear();
			try {
				int r = channel.read(ctrl); //READ ONLY FROM CONNECTED PEER
				if(r <= 0) {
					LockSupport.parkNanos(backoffNs);
					continue;
				}
				
				// Completion signal kontrolü (8 byte)
				if(r == 8) {
					ctrl.flip();
					int magic = ctrl.getInt();
					int receivedFileId = ctrl.getInt();
					
					if(magic == 0xDEADBEEF && receivedFileId == (int)fileId) {
						System.out.println("🎉 Transfer completion signal received from receiver!");
						if(onTransferComplete != null) {
							try {
								onTransferComplete.run();
							} catch(Exception e) {
								System.err.println("Error in completion callback: " + e);
							}
						}
						break; // Exit listener loop
					}
					continue;
				}
				
				// NACK Frame tam boyut kontrolü - sabit 20 byte olmalı
				if(r != NackFrame.SIZE) {
					System.err.println("Invalid frame size: expected " + NackFrame.SIZE + " (NACK) or 8 (completion), received " + r + " bytes");
					continue;
				}
				
				ctrl.flip();
				
				// Buffer'ın tam olarak frame size kadar olduğunu kontrol et
				if(ctrl.remaining() != NackFrame.SIZE) {
					System.err.println("Buffer remaining mismatch: expected " + NackFrame.SIZE + ", got " + ctrl.remaining());
					continue;
				}
				
				long fid = NackFrame.fileId(ctrl);
				if(fid != fileId) {
					// Farklı dosya ID'si - sessizce atla
					continue;
				}
				
				int base = NackFrame.baseSeq(ctrl);
				long mask = NackFrame.mask64(ctrl);
				
				// Base sequence validation
				if(base < 0 || base >= totalSeq) {
					System.err.println("Invalid base sequence: " + base + " (total: " + totalSeq + ")");
					continue;
				}
				
				// Transfer completion kontrolü - eğer base + 64 >= totalSeq ve tüm bitler 1 ise tamamlanmış
				int remainingPackets = totalSeq - base;
				if(remainingPackets <= 64) {
					// Son 64 paket içinde - tümünün alındığını kontrol et
					long expectedMask = (1L << remainingPackets) - 1; // remainingPackets kadar bit 1
					if((mask & expectedMask) == expectedMask) {
						System.out.println("Transfer completed detected by sender! All packets received.");
						if(onTransferComplete != null) {
							try {
								onTransferComplete.run();
							} catch(Exception e) {
								System.err.println("Transfer completion callback error: " + e);
							}
						}
						return; // Listener'ı sonlandır
					}
				}
				
				// 64-bit mask'teki her bit için kontrol et
				int lossCount = 0;
				int ackCount = 0;
				for(int i = 0; i < 64; i++){
					int seq = base + i;
					if(seq >= totalSeq) break; // Son paketten sonrası için dur
					
					boolean received = ((mask >>> i) & 1L) == 1L;
					if(!received) {
						// Sadece geçerli sequence number'ları queue'ya ekle
						if(seq >= 0 && seq < totalSeq) {
							boolean added = retxQueue.offer(seq);
							if(!added) {
								System.err.println("Failed to add seq " + seq + " to retransmission queue");
							}
							lossCount++;
						}
					} else {
						ackCount++;
					}
				}
				
				// Congestion control feedback
				if(congestionControl != null) {
					if(ackCount > 0) {
						congestionControl.onPacketAcked(ackCount);
					}
					if(lossCount > 0) {
						congestionControl.onPacketLoss(lossCount);
					}
				}
				
			}catch(IOException e) {
				System.out.println("IO Error: " + e);
				LockSupport.parkNanos(backoffNs);
			}
		}
	}
}
