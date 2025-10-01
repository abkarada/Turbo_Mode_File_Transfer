import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public class SHA256_Packet {
	public static final int OFF_SIGNAL = 0;
	public static final int OFF_FILE_ID = 1;
	public static final int OFF_FILE_SIZE = 9;
	public static final int OFF_TOTAL_SEQ = 17;
	public static final int OFF_SIGNATURE = 21;
	public static final int OFF_FLAG = 21;  // ACK paketlerinde kullanılır, SYN paketlerinde signature kullanılır
	public static final byte SYN = 0x01;
	public static final byte ACK = 0x10;
	public static final byte SYN_ACK = 0X11;
	public static final byte APPROVED = 0x0a;
	public static final byte NOT_APPROVED = 0xb;
	public static final byte RE_TRY_REQUIRED = 0xc;
	public static final byte  FINISHED = 0xd;

	public static final int HEADER_SIZE = 53;
	public static final int ACK_HEADER_SIZE = 21;
	public static final int SIGNATURE_LEN = 32; // classic 32-bit I mean you could fucking sense that you loser get lost
	
	private final ByteBuffer header;
	
	public SHA256_Packet() {
		this.header = ByteBuffer.allocateDirect(53).order(ByteOrder.BIG_ENDIAN);
	}
	
	public ByteBuffer get_header()
	{
		return header;
	}
	
	public void fill_signature(long fileId, long fileSize, int totalSeq, byte[] signature) {
		header.clear();
		
		header.put(OFF_SIGNAL, SYN);
		header.putLong(OFF_FILE_ID, fileId);
		header.putLong(OFF_FILE_SIZE, fileSize);
		header.putInt(OFF_TOTAL_SEQ, totalSeq);
		
		header.position(OFF_SIGNATURE);
		header.put(signature);
		
		header.limit(HEADER_SIZE);
		header.position(0);
		
	}

	public void make_ack(long fileId, long fileSize, int totalSeq, byte STATUS){
		header.clear();
	
		header.put(OFF_SIGNAL, ACK);
		header.putLong(OFF_FILE_ID, fileId);
		header.putLong(OFF_FILE_SIZE, fileSize);
		header.putInt(OFF_TOTAL_SEQ, totalSeq);
		header.put(OFF_FLAG, STATUS);
	
		header.limit(22);
		header.position(0);

	}

	public void make_syn_ack(long fileId, long fileSize, int totalSeq, byte STATUS){
		header.clear();

		header.put(OFF_SIGNAL, SYN_ACK);
		header.putLong(OFF_FILE_ID, fileId);
		header.putLong(OFF_FILE_SIZE, fileSize);
		header.putInt(OFF_TOTAL_SEQ, totalSeq);
		header.put(OFF_FLAG, STATUS);

		header.limit(22);
		header.position(0);
	}
	
	public void reset_for_retransmitter() {
			header.position(0).limit(HEADER_SIZE);
	}

	// ✅ Absolute position metodları - buffer position'ı değiştirmez
	public static byte get_signal(ByteBuffer p){ return p.get(OFF_SIGNAL); }
	public static long get_fileId(ByteBuffer p) { return p.getLong(OFF_FILE_ID); }
	public static long get_fileSize(ByteBuffer p) { return p.getLong(OFF_FILE_SIZE);}
	public static int get_total_seq(ByteBuffer p) { return p.getInt(OFF_TOTAL_SEQ); }
	public static byte get_status(ByteBuffer p) { return p.get(OFF_FLAG); }
	
	/**
	 * Buffer'ı etkilemeden packet validation yapar
	 */
	public static boolean isValidSYNPacket(ByteBuffer buffer, long expectedFileId) {
		if(buffer == null || buffer.remaining() < HEADER_SIZE) {
			return false;
		}
		
		try {
			byte signal = get_signal(buffer);
			long fileId = get_fileId(buffer);
			return (signal == SYN && fileId == expectedFileId);
		} catch (Exception e) {
			return false;
		}
	}
	
	/**
	 * Buffer'ı etkilemeden SYN_ACK packet validation yapar
	 */
	public static boolean isValidSYNACKPacket(ByteBuffer buffer, long expectedFileId) {
		if(buffer == null || buffer.remaining() < ACK_HEADER_SIZE) return false;
		
		try {
			byte signal = get_signal(buffer);
			long fileId = get_fileId(buffer);
			return (signal == SYN_ACK && fileId == expectedFileId);
		} catch (Exception e) {
			return false;
		}
	}
	public static byte[] get_signature(ByteBuffer p) {
		if(p == null) throw new IllegalArgumentException("ByteBuffer cannot be null");
		if(p.remaining() < HEADER_SIZE) throw new IllegalArgumentException("Buffer too small for signature");
		
		byte[] sign = new byte[SIGNATURE_LEN];
		
		ByteBuffer copy = p.duplicate();
		copy.position(OFF_SIGNATURE);
		
		if(copy.remaining() < SIGNATURE_LEN) {
			throw new IllegalArgumentException("Not enough data for signature");
		}
		
		copy.get(sign);
		return sign;
	}

	public static byte compareSignatures(byte[] candidate, byte[] original){
		 if(Arrays.equals(candidate, original)){
			return APPROVED;
		 }
		 return NOT_APPROVED;
	}
}
