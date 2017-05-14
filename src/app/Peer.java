/*
 * Andrew Lee
 */
package app;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Peer.java
 * This class represents the connection with a peer and manages the message communication
 * between both hosts. When blocks are received by the peer, the piece is reassembled, verified
 * against its hash in the metainfo, and subsequently written into the file.
 */
public class Peer extends Thread
{
	/** The 20-byte array containing the peer ID of the remote host. */
	protected byte[] peerID;
	/** The port which the socket to the peer is connected on. */
	protected int port;
	/** The IP address which the socket to the peer is connected on. */
	protected String ip;

	/** True if the local host is choking the peer, false otherwise. */
	boolean isChokingPeer = true;
	/** True if the local host is interested in the peer, false otherwise. */
	boolean isInterestedInPeer = false;
	/** True if the local host is being choked by the peer, false otherwise. */
	boolean isChokedByPeer = true;
	/** True if the peer is interested in the local host. */
	boolean isPeerInterested = false;	// semantically nonsensical; is peer interested in local host

	/** Bitfield of the peer. */
	boolean[] bitfield;

	// Socket which connects to the peer.
	private Socket sock;

	/** InputStream object. */
	protected InputStream in;
	/** OutputStream object. */
	protected OutputStream out;

	// The index that the download is requesting at any given time
	private int currentPieceIndex = -1;
	// The byte offset of the piece that the download is requesting at any given time
	private int currentByteOffset = 0;

	// The length of the piece (which is shorter for the last piece)
	private int piece_length;
	// The total length of the file
	private int file_length;
	// The length of a block within a piece
	private int blockSize;	// 2^14
	// The total number of pieces 
	private int numPieces;

	// ByteArrayOutputStream used to store blocks for a piece
	private ByteArrayOutputStream piece;

	//	private int totalBytesWritten = 0;

	// True if the peer connection is active, false otherwise
	private boolean isRunning = true;
	/** Total amount of bytes downloaded. */
	long totalDownloaded = 0L;

	/** TorrentClient object. */
	public TorrentClient client;
	/** PeerKeepAliveTask object for keep-alive message sending. */
	private PeerKeepAliveTask keepAliveTask;

	/**
	 * Constructor for the Peer class.
	 * @param peerID the peer ID of the peer as a 20-byte array
	 * @param port the port number of the peer
	 * @param ip the IP address of the peer
	 * @param client the TorrentClient object managing the list of peers
	 */
	public Peer(byte[] peerID, int port, String ip, TorrentClient client)
	{
		super("Peer ID: " + peerID);
		this.peerID = peerID;
		this.port = port;
		this.ip = ip;

		this.client = client;
		// calculate the relevant piee and block information
		piece_length = client.info.piece_length;
		file_length = client.info.file_length;
		blockSize = 16384;	// 2^14 TODO actually retrieve value from metainfo
		numPieces = client.info.piece_hashes.length;
		// start the timer for keep-alive messages
		keepAliveTask = new PeerKeepAliveTask(this);
		// initialize the local bitfield
		bitfield = new boolean[client.info.piece_hashes.length];
		Arrays.fill(bitfield, false);
	}

	/**
	 * Initializes the thread running the Peer object.
	 * 
	 * @return true if the connection to the peer and the verification of the handshake was successful, false otherwise
	 */
	public boolean init()
	{
		try {
			isRunning = true;
			//			currentTime = System.currentTimeMillis();
			connect();

			// create data streams to peer
			DataOutputStream dos = new DataOutputStream(out);
			DataInputStream dis = new DataInputStream(in);

			if (dis == null || dos == null) {
				System.err.println("Error: Data streams cannot be created.");
				disconnect();
				return false;
			}
			// try to send a handshake message
			dos.write(Peer.generateHandshake(TorrentClient.peerID, client.info.info_hash.array()));
			dos.flush();

			byte[] peerResponse = new byte[68];
			// Set a 10 second timeout to get the handshake reply
			sock.setSoTimeout(10000);
			dis.readFully(peerResponse);
			// Now set the timeout to the default 2 minute interval
			sock.setSoTimeout(120000);

			// verify the handshake received by the epeer
			if (!checkHandshake(client.info.info_hash.array(), peerResponse)) {
				return false;
			}

			System.out.println("Handshake from peer: " + new String(peerResponse, "UTF-8"));

			if (client.currentUnchoked < TorrentClient.MAX_UNCHOKED) {	// torrent client can take another peer
				isChokingPeer = false;
				client.currentUnchoked++;
			}
			else {	// client can't take another peer at this time
				isChokingPeer = true;
			}

			// Determine if the client can pick up from the last verified index
			currentPieceIndex = getResumeIndex();

			start();

			return true;
		}
		catch (Exception e)
		{	e.printStackTrace();	}
		return false;
	}

	/**
	 * Times a connection to the peer and the sending and receiving of a ping in nanoseconds.
	 * @return the round trip time to send and receive a ping, in nanoseconds
	 * @throws IOException
	 */
	public long pingPeer() throws IOException
	{
		long startTime = System.nanoTime();
		connect();
		// create data streams to peer
		DataOutputStream dos = new DataOutputStream(out);
		DataInputStream dis = new DataInputStream(in);

		if (dis == null || dos == null) {
			System.err.println("Error: Data streams cannot be created.");
			disconnect();
			return -1;
		}
		// send a small byte to ping
		dos.write(0);
		dos.flush();

		int n = dis.read();
		long endTime = System.nanoTime();
		disconnect();
		
		return endTime - startTime;
	}

	/**
	 * @see java.lang.Thread#run()
	 */
	public void run()
	{
		// run for as long as the socket is connecting and running
		while (sock != null && !sock.isClosed() && isRunning)
		{
			Message incMsg = null;
			try {
				incMsg = Message.decode(in);
			}
			catch (IOException ioe) {
				ioe.printStackTrace();
				break;
			}
			if (incMsg != null) {	// received some message
				client.receiveMessage(new PeerMessage(this, incMsg));
			}
		}	// end of while (sock != null && !sock.isClosed() && isRunning)
	}
	
	
	public Message.Request getNextRequest()
	{
		int piece_length = client.info.piece_length;
		int file_length = client.info.file_length;
		int blockSize = 16384;
		int numPieces = client.info.piece_hashes.length;
		
		if (currentPieceIndex == -1) {
			if ((currentPieceIndex = client.getNextPieceIndex()) == -1) {
				System.err.println("Failed to get the next piece. Possibly completed download.");
				return null;
			}
		}
		if (currentPieceIndex == (numPieces - 1)) {
			piece_length = file_length % client.info.piece_length;
		}
		
		if ((currentByteOffset + blockSize) > piece_length) {
			blockSize = piece_length % blockSize;
		}
		
		Message.Request request = new Message.Request(currentPieceIndex, currentByteOffset, blockSize);
		
		if ((currentByteOffset + blockSize) >= piece_length) {
			currentPieceIndex = -1;
			currentByteOffset = 0;
		}
		else {
			currentByteOffset += blockSize;
		}
		
		return request;
	}


//	/**
//	 * Calculates the piece index, byte offset, and request size of the next Request message.
//	 * Afterwards it updates the internal counters tracking piece index and byte offset.
//	 * This method uses the "in-sequence" procedure of retrieving pieces.
//	 * 
//	 * @return a Request Message object with the next block of data to be requested
//	 */
//	public Message.Request getNextRequest()
//	{
//		// check if last piece is being requested, the one that's smaller than the rest
//		// additionally, check if the piece_length has been modified (avoid second modification)
//		if (currentPieceIndex == (numPieces - 1) && piece_length == 32768) {
//			System.out.println("File length: " + file_length);
//			System.out.println("Current piece length: " + piece_length);
//			piece_length = file_length % piece_length;
//			System.out.println("Last piece length is " + piece_length);
//		}
//		// check if requesting a full block will exceed the remainder of a piece
//		if ((currentByteOffset + blockSize) > piece_length) {
//			System.out.println("Current byte offset: " + currentByteOffset);
//			System.out.println("Current block size: " + blockSize);
//			blockSize = piece_length % blockSize;
//			System.out.println("New block size: " + blockSize);
//		}
//		// create a new Request message with the calculated piece index, byte offset, and block size
//		Message.Request request = new Message.Request(currentPieceIndex, currentByteOffset, blockSize);
//
//		// determine if the recent Request message will request the last block of a piece
//		if ((currentByteOffset + blockSize) >= piece_length) {
//			currentPieceIndex++;
//			currentByteOffset = 0;
//		}
//		else {	// otherwise increment the offset by the amount requested
//			currentByteOffset += blockSize;
//		}
//
//		return request;
//	}

	/**
	 * Determines whether the current ByteArrayOutputStream has a complete piece.
	 * @return true if all blocks compose the piece, false otherwise
	 */
	private boolean isPieceIncomplete()
	{
		// TODO if the block size results in >2 blocks, this method will cause a runtime bug
		if (currentByteOffset != 0) {
			return true;
		}
		return false;
	}

	/**
	 * Writes a block of a piece into a ByteBuffer, and if the ByteBuffer is a complete piece, verify
	 * its hash to the hash in the metainfo. If the piece is validated, it is written into the output
	 * file. Whether or not the piece was validated, it is discarded for either the next piece or the
	 * same piece again.
	 * 
	 * @param pieceMsg
	 * @param hashes
	 * @param client
	 * @return
	 */
	public boolean appendToPiece(Message.Piece pieceMsg, ByteBuffer[] hashes, TorrentClient client)
	{
		// check if the message's piece index is the final piece
		int currentPieceLength;
		if (pieceMsg.index == (client.info.piece_hashes.length - 1)) {
			currentPieceLength = client.info.file_length % client.info.piece_length;
		}
		else {	// is some piece whose size matches the piece length specified in the torrent info
			currentPieceLength = client.info.piece_length;
		}

		if (piece == null) {	// piece does not yet exist on local host
			piece = new ByteArrayOutputStream();
		}
		// now write the data contained in the message into the ByteBuffer
		try {
			//piece.write(pieceMsg.block, pieceMsg.offset, pieceMsg.block.length);
			piece.write(pieceMsg.block, 0, pieceMsg.block.length);
		}
		catch (Exception e)
		{	e.printStackTrace();	}

		// Have not downloaded any pieces yet
		//		if (currentPieceIndex == -1) {
		//			totalBytesWritten = 0;
		//		client.peerBitfield[previousIndex] = false;

		if (currentPieceIndex != -1) {	// if it equals -1, that means it's not working on a certain piece
			System.out.println("Piece not yet complete.");
			return false;
		}

		try {
			// check if the client wrote to the file without any problems
			if (client.updateFile(pieceMsg, hashes[pieceMsg.index], piece.toByteArray())) {
				totalDownloaded += currentPieceLength;
				System.out.println(">>Total downloaded: " + totalDownloaded);
				// update bitfield and discard piece after writing it
				client.localHostBitfield[pieceMsg.index] = true;
				piece = null;
				return true;
			}
			else {	// client reported bad piece; discard
				piece = null;
			}
		}
		catch (Exception e) {
			e.printStackTrace();
			piece = null;
		}
		//		}	// end of if (currentPieceIndex == -1)
		return false;
	}

	/**
	 * Attempts connection with peer and opens associated streams.
	 * 
	 * @throws IOException
	 */
	public synchronized void connect() throws IOException
	{
		sock = new Socket(ip, port);
		in = sock.getInputStream();
		out = sock.getOutputStream();
	}

	/**
	 * Disconnects from a peer and frees the socket and associated streams.
	 * 
	 * @throws IOException
	 */
	public synchronized void disconnect() throws IOException
	{
		if (!isChokingPeer) {	// peer was receiving information prior to disconnect
			client.currentUnchoked--;
		}
		try {
			if (sock != null) {
				keepAliveTask.isRunning = false;
				keepAliveTask.interrupt();
				sock.close();
			}
		}
		catch (IOException ioe)
		{	ioe.printStackTrace();	}
		finally {
			sock = null;
			in = null;
			out = null;
			isRunning = false;
		}
	}

	/**
	 * Creates the peer handshake message that the torrent client sends to a peer.
	 * 
	 * @param peerID
	 * @param info_hash
	 * @return
	 */
	public static byte[] generateHandshake(byte[] peerID, byte[] info_hash)
	{
		int index = 0;
		byte[] handshakeMsg = new byte[68];
		// append byte 19
		handshakeMsg[index] = 0x13;	// byte 19
		index++;
		// append "BitTorrent protocol"
		byte[] BitTorrentProtocolBytes = 
			{'B', 'i', 't', 'T', 'o', 'r', 'r', 'e', 'n', 't', ' ',
					'p', 'r', 'o', 't', 'o', 'c', 'o', 'l'};
		// copy the "BitTorrent protocol" byte array right after byte 19 in the handshake message
		System.arraycopy(BitTorrentProtocolBytes, 0, handshakeMsg, index, BitTorrentProtocolBytes.length);
		index += BitTorrentProtocolBytes.length;
		// append the eight 0's
		byte[] reserved = new byte[8];
		System.arraycopy(reserved, 0, handshakeMsg, index, reserved.length);
		index += reserved.length;
		// append the info hash of the metainfo
		System.arraycopy(info_hash, 0, handshakeMsg, index, info_hash.length);
		index += info_hash.length;
		// append the peer ID this client has
		System.arraycopy(peerID, 0, handshakeMsg, index, peerID.length);

		return handshakeMsg;
	}

	/**
	 * Returns the boolean value of comparing the info hash given by the peer to the
	 * info hash in the metainfo.
	 * 
	 * @param info_hash
	 * @param responseMsg
	 * @return true if the two hashes are equal, false otherwise
	 */
	public boolean checkHandshake(byte[] info_hash, byte[] responseMsg)
	{
		// copy the 20-byte info hash contained in the response message at index 28
		byte[] peer_info_hash = new byte[20];
		System.arraycopy(responseMsg, 28, peer_info_hash, 0, peer_info_hash.length);
		// return the result of comparing the two hashes
		return Arrays.equals(peer_info_hash, info_hash);
	}

	/**
	 * Sends the specified Message to the peer.
	 * @param msg Message object to be sent
	 * @throws IOException
	 */
	public synchronized void sendMessage(Message msg) throws IOException
	{
		if (out == null) {
			throw new IOException("Error: " + this + "can't send a message on a null socket.");
		}
		System.out.println("Sending " + msg + " to " + this);
		Message.encode(msg, out);
		keepAliveTask.interrupt();
	}

	/**
	 * Determines the index at which the client should resume a download.
	 * @return an int representing the next index with an unverified piece
	 */
	public int getResumeIndex()
	{
		int i;
		for (i = 0; i < client.localHostBitfield.length; i++)
		{
			if (client.localHostBitfield[i] == false) {	// this piece was not verified
				return i;
			}
		}
		return i;
	}

	/**
	 * Chokes a peer by sending a CHOKE message.
	 */
	public void choke()
	{
		try {
			sendMessage(Message.CHOKE);
		}
		catch (IOException ioe)
		{	ioe.printStackTrace();	}
		isChokingPeer = true;
	}

	/**
	 * Unchokes a peer by sending an UNCHOKE message.
	 */
	public void unchoke()
	{
		try {
			sendMessage(Message.UNCHOKE);
		}
		catch (IOException ioe)
		{	ioe.printStackTrace();	}
		isChokingPeer = false;
	}

}
