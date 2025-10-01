import java.nio.ByteBuffer;
import java.nio.ByteOrder;


public class HandShake_Packet {

public static final byte SYN = 0x01;
public static final byte ACK = 0x10;
public static final byte SYN_ACK = 0x11;

	public static final int OFF_SIG = 0;
	public static final int OFF_FILE_ID = 1;
	public static final int OFF_FILE_SIZE = 9;
	public static final int OFF_TOTAL_SEQ = 13;

	public static final int HEADER_SIZE = 17;
	private ByteBuffer hnd_shk_pkt;
	public HandShake_Packet(){
		this.hnd_shk_pkt = ByteBuffer.allocateDirect(HEADER_SIZE)
					.order(ByteOrder.BIG_ENDIAN);	
	}
	
	public ByteBuffer get_header()
	{
		return hnd_shk_pkt;
	}

	public void make_SYN(long file_Id, int file_size, int total_seq){
		hnd_shk_pkt.clear();
		
		hnd_shk_pkt.put(OFF_SIG, SYN);
		hnd_shk_pkt.putLong(OFF_FILE_ID, file_Id);
		hnd_shk_pkt.putInt(OFF_FILE_SIZE, file_size);
		hnd_shk_pkt.putInt(OFF_TOTAL_SEQ, total_seq);
		
		hnd_shk_pkt.limit(HEADER_SIZE);
		hnd_shk_pkt.position(0);
	}
	
	public void make_ACK(long file_Id, int file_size, int total_seq)
	{
		hnd_shk_pkt.clear();

		hnd_shk_pkt.put(OFF_SIG, ACK);
		hnd_shk_pkt.putLong(OFF_FILE_ID, file_Id);
		hnd_shk_pkt.putInt(OFF_FILE_SIZE, file_size);
		hnd_shk_pkt.putInt(OFF_TOTAL_SEQ, total_seq);
		
		hnd_shk_pkt.limit(HEADER_SIZE);
		hnd_shk_pkt.position(0);
	}

	public void make_SYN_ACK(long file_Id)
	{
		hnd_shk_pkt.clear();

		hnd_shk_pkt.put(OFF_SIG, SYN_ACK);
		hnd_shk_pkt.putLong(OFF_FILE_ID, file_Id);

		hnd_shk_pkt.limit(9);
		hnd_shk_pkt.position(0);

	}

	public void resetForRetransmitter()
	{
		hnd_shk_pkt.position(0).limit(HEADER_SIZE);
	}
	public static byte get_signal(ByteBuffer b) { return b.get(OFF_SIG); }
	public static long get_file_Id(ByteBuffer b){ return b.getLong(OFF_FILE_ID); }
	public static int get_file_size(ByteBuffer b) { return b.getInt(OFF_FILE_SIZE); }
	public static int get_total_seq(ByteBuffer b) { return b.getInt(OFF_TOTAL_SEQ); }
}
