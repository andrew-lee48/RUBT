/*
 * Andrew Lee
 */
package app;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Message.java
 * This class defines the different types of messages used in the Bittorrent
 * protocol and contains several static classes for communication between
 * peers.
 */
public class Message
{
	/** Byte ID for the keep-alive message. */
	public static final byte KEEP_ALIVE_ID = -1;
	
	/** Byte ID for the choke message. */
	public static final byte CHOKE_ID = 0;
	
	/** Byte ID for the unchoke message. */
	public static final byte UNCHOKE_ID = 1;
	
	/** Byte ID for the interested message. */
	public static final byte INTERESTED_ID = 2;
	
	/** Byte ID for the uninterested message. */
	public static final byte UNINTERESTED_ID = 3;
	
	/** Byte ID for the have message. */
	public static final byte HAVE_ID = 4;
	
	/** Byte ID for the bitfield message. */
	public static final byte BITFIELD_ID = 5;
	
	/** Byte ID for the request message. */
	public static final byte REQUEST_ID = 6;
	
	/** Byte ID for the piece message. */
	public static final byte PIECE_ID = 7;
	
	/** Static keep-alive message definition. */
	public static final Message KEEP_ALIVE = new Message(0, (byte) 255);
	
	/** Static choke message definition. */
	public static final Message CHOKE = new Message(1, CHOKE_ID);
	
	/** Static unchoke message definition. */
	public static final Message UNCHOKE = new Message(1, UNCHOKE_ID);
	
	/** Static interested message definition. */
	public static final Message INTERESTED = new Message(1, INTERESTED_ID);
	
	/** Static uninterested message definition. */
	public static final Message UNINTERESTED = new Message(1, UNINTERESTED_ID);
	
	/** Constant String array ordered relative to their respective ID's. */
	private static final String[] TYPE_NAMES = new String[] {"Choke", "Unchoke", "Interested", "Uninterested", "Have", "Bitfield", "Request", "Piece"};
	
	/** ID value of the message. */
	protected final byte id;
	
	/** Length prefix of the message. */
	protected final int length;
	
	/**
	 * Constructor for the Message class.
	 * 
	 * @param length length prefix
	 * @param id id
	 */
	public Message(final int length, final byte id)
	{
		this.id = id;
		this.length = length;
	}
	
	/**
	 * Encodes the payload inside of the message.
	 * This method is meant to be overriden.
	 * 
	 * @param dos
	 * @throws IOException
	 */
	public void encodePayload(DataOutputStream dos) throws IOException
	{	return;	}
	
	/**
	 * Static class for the Have type of Message.
	 */
	public static final class Have extends Message
	{
		/** Piece index that the sender has received and verified. */
		public final int index;
		
		/**
		 * Constructor for the Have class.
		 * @param index the index of the piece which was received and verified
		 */
		public Have(final int index)
		{
			super(5, HAVE_ID);
			this.index = index;
		}
		
		/** Encodes the payload inside of the message. */
		public void encodePayload(DataOutputStream dos) throws IOException
		{
			dos.writeInt(index);
			return;
		}
	}
	
	/**
	 * Static class for the Bitfield type of Message.
	 */
	public static final class Bitfield extends Message
	{
		/** Bitfield that the sender possesses of the pieces. */
		public final byte[] bitfield;
		
		/**
		 * Constructor for the Bitfield class.
		 * @param bitfield the bitfield of the local host, represented in a boolean array
		 */
		public Bitfield(final byte[] bitfield)
		{
			super(bitfield.length + 1, BITFIELD_ID);
			this.bitfield = bitfield;
		}
		
		/** Encodes the payload inside of the message. */
		public void encodePayload(DataOutputStream dos) throws IOException
		{
			dos.write(bitfield);
			return;
		}
	}
	
	/**
	 * Static class for the Request type of Message.
	 */
	public static final class Request extends Message
	{
		/** Piece index that the sender is requesting a copy of. */
		final int index;
		/** Byte offset of the piece index that the sender is requesting a copy of. */
		final int offset;
		/** Message length prefix of the message. */
		final int msgLength;
		
		/**
		 * Constructor for the Request class.
		 * @param index the index of the piece being requested
		 * @param offset the byte offset of the piece that the data should start at
		 * @param length the length of the data
		 */
		public Request(final int index, final int offset, final int length)
		{
			super(13, REQUEST_ID);
			this.index = index;
			this.offset = offset;
			msgLength = length;
		}
		
		/**
		 * Returns the length prefix, index, offset, and block size contained in the message.
		 */
		public String toString()
		{
			return new String("Length: " + length + " ID: " + id
					+ " Index: " + index + " Offset: " + offset
					+ " Block: " + msgLength);
		}
		
		/**
		 * Encodes the payload inside of the message.
		 */
		public void encodePayload(DataOutputStream dos) throws IOException
		{
			dos.writeInt(index);
			dos.writeInt(offset);
			dos.writeInt(msgLength);
		}
	}
	
	/**
	 * Static class for the Piece type of Message.
	 */
	public static final class Piece extends Message
	{
		/** Piece index that the sender is transmitting a copy of. */
		final int index;
		/** Byte offset of the piece index that the sender is transmitting a copy of. */
		final int offset;
		/** Block of data of the piece index. */
		final byte[] block;
		
		/**
		 * Constructor for the Piece class.
		 * @param index the index of the piece being sent
		 * @param offset the byte offset within the piece that the data starts at
		 * @param block the length of the data
		 */
		public Piece(final int index, final int offset, final byte[] block)
		{
			super(9 + block.length, PIECE_ID);
			this.index = index;
			this.offset = offset;
			this.block = block;
		}
		
		/**
		 * Return the message id, length, and piece index in the message.
		 */
		public String toString()
		{
			return new String("ID: " + id + " Length: " + length + " Index: " + index);
		}
		
		/**
		 * Encodes the payload inside of the message.
		 */
		public void encodePayload(DataOutputStream dos) throws IOException
		{
			dos.writeInt(index);
			dos.writeInt(offset);
			dos.write(block);
		}
	}
	
	/**
	 * Decodes a message and parses it to determine its type.
	 * 
	 * @param input InputStream object
	 * @return a message with variable id
	 * @throws IOException
	 */
	public static Message decode(final InputStream input) throws IOException
	{
		DataInputStream dataIn = new DataInputStream(input);
		
		// read the first part of the message, length prefix
		int length = dataIn.readInt();
		
		if (length == 0) {	// a length of 0 means it's a keep-alive message
			return KEEP_ALIVE;
		}
		
		// read the second part of the message, message id
		byte id = dataIn.readByte();
		
		switch (id) {
		case (CHOKE_ID): {
			return CHOKE;
		}
		case (UNCHOKE_ID): {
			return UNCHOKE;
		}
		case (INTERESTED_ID): {
			return INTERESTED;
		}
		case (UNINTERESTED_ID): {
			return UNINTERESTED;
		}
		case (HAVE_ID): {
			int index = dataIn.readInt();
			return new Have(index);
		}
		case (BITFIELD_ID): {
			byte[] bitfield = new byte[length - 1];
			dataIn.readFully(bitfield);
			return new Bitfield(bitfield);
		}
		case (PIECE_ID): {
			int pieceIndex = dataIn.readInt();
			int offset = dataIn.readInt();
			byte[] block = new byte[length - 9];
			dataIn.readFully(block);
			return new Piece(pieceIndex, offset, block);
		}
		case (REQUEST_ID): {
			int pieceIndex = dataIn.readInt();
			int offset = dataIn.readInt();
			length = dataIn.readInt();
			return new Request(pieceIndex, offset, length);
		}
		}	// end of switch (id)
		return null;
	}	// end of decode(InputStream)
	
	
	/**
	 * Encodes a message prior to sending.
	 * 
	 * @param message Message
	 * @param out OutputStream object
	 * @throws IOException
	 */
	public static void encode(final Message message, final OutputStream out) throws IOException
	{
		if (message != null) {	// validate message
			DataOutputStream dos = new DataOutputStream(out);
			dos.writeInt(message.length);	// first write length prefix
			if (message.length > 0) {	// message is not a keep-alive message
				dos.write(message.id);
				message.encodePayload(dos);
			}
			dos.flush();
		}
	}
	
	/**
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString()
	{
		if (length == 0) {
			return "Keep-Alive";
		}
		return TYPE_NAMES[id];
	}

}
