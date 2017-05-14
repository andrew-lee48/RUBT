/*
 * Andrew Lee
 */
package app;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Timer;
import java.util.concurrent.LinkedBlockingQueue;

import GivenTools.TorrentInfo;

/**
 * TorrentClient.java
 * This class creates, manages, and closes connections between the tracker and
 * a list of peers that it maintains. It is responsible for initializing them
 * which includes generating a peer ID to identify itself, receive data from a
 * peer and perform SHA1 hash checks on pieces, and write verified pieces into
 * the output file.
 */
public class TorrentClient extends Thread
{
	/** Prefix of an official Rutgers peer that's guaranteed to be a seed. */
	public static final byte[] RU_PEER_ID = new byte[] {'-', 'R', 'U', '1', '1', '0', '3'};
	/** Constant for the lower bound of ports usable for the client. */
	public static final int MIN_PORT_RANGE = 6881;
	/** Constant for the upper bound of ports usable for the client. */
	public static final int MAX_PORT_RANGE = 6889;

	/** Constant for the upper bound of peers that are unchoked by this client. */
	public static final int MAX_UNCHOKED = 4;

	/** TrackerConnection object. */
	TrackerConnection tConn;
	/** TorrentInfo object. */
	TorrentInfo info;

	/** File for the output file. */
	File outputFile;

	/** List of Peers populated from the tracker's response. */
	ArrayList<Peer> peerList;

	ServerSocket serverSock;

	/** Port number for this client. */
	int listenPort = -1;

	/** Queue used to buffer incoming messages from peers. */
	LinkedBlockingQueue<PeerMessage> messageQueue;

	/** Self-identifying peer ID. */
	public static byte[] peerID;

	/** Number of peer that are unchoked by this peer. */
	public int currentUnchoked = 0;

	/** Determines if the client is running. */
	boolean isRunning = false;

	/** Determines if the client is downloading the file. */
	boolean isDownloading = true;

	/** Bitfield containing indices of verified pieces. */
	public boolean[] localHostBitfield;

	/** Determines if the client has the complete file. */
	public static boolean haveCompleteFile = false;

	// Time that the peer connection began, in nanoseconds
	private long startTime = 0L;

	//	/** Peer that was selected to download from. */
	//	public Peer selectedPeer = null;
	//	/** Round trip time to ping this peer. */
	//	public long RTT;

	/**
	 * Constructor for the TorrentClient class.
	 * @param info TorrentInfo object
	 * @param file File object for output
	 */
	public TorrentClient(TorrentInfo info, File file)
	{
		this.info = info;
		outputFile = file;
		peerList = new ArrayList<Peer>();
	}


	//	public int selectPort()
	//	{
	//		for (int i = MIN_PORT_RANGE; i <= MAX_PORT_RANGE; i++)
	//		{
	//			try {
	//				serverSock = new ServerSocket(i);
	//				return listenPort;
	//			}
	//			catch (IOException ioe)
	//			{	ioe.printStackTrace();	}
	//		}
	//		System.err.print("Error: Could not open ServerSocket on port range " + MIN_PORT_RANGE + "~" + MAX_PORT_RANGE + ".");
	//		return -1;
	//	}

	/**
	 * Closes any connections to Peers.
	 * @throws IOException
	 */
	public void close() throws IOException
	{
		isRunning = false;
		if (peerList != null) {
			for (Peer peer : peerList)
			{ 
				try {
					peer.disconnect();
				}
				catch (Exception e)
				{	e.printStackTrace();	}
			}
		}	// end of if (peerList != null)
	}

	/**
	 * @see java.lang.Thread#run()
	 */
	public void run()
	{
		while (isRunning)
		{
			try {
				decode();
			}
			catch (Exception e)
			{	e.printStackTrace();	}
		}
	}

	/**
	 * Initializes the client by generating and storing a peer ID, selecting a port
	 * number, and initializing any relevent fields.
	 * @throws IOException
	 */
	public void init() throws IOException
	{
		startTime = System.nanoTime();
		peerID = Utils.generatePeerID();
		//		listenPort = selectPort();
		listenPort = MIN_PORT_RANGE;
		tConn = new TrackerConnection(info, peerID, listenPort, this);

		ArrayList<Peer> returnedPeerList = tConn.update(TrackerConnection.EVENT_STARTED);

		// TODO add timings for keep-alive

		tConn.timer = new Timer();
		tConn.trackerUpdate = new TrackerUpdate(tConn, this);
		tConn.timer.schedule(tConn.trackerUpdate, tConn.interval * 1000, tConn.interval * 1000);

		messageQueue = new LinkedBlockingQueue<PeerMessage>();

		if (returnedPeerList != null) {
			for (Peer p : returnedPeerList)
			{
				// Extract the first six bytes of the peer ID to see if it's the RUBT11 peer
				byte[] id = new byte[7];

				System.arraycopy(p.peerID, 0, id, 0, 7);

								if (!Arrays.equals(id, TorrentClient.RU_PEER_ID)) {	// not a Rutgers peer, ignore
									continue;
								}

				// It's no longer needed to track RTT times
				//				long avgRTT = getAverageRTT(p);
				//				System.out.println("Average RTT: " + avgRTT);
				//				
				//				if (selectedPeer == null) {	// peer hasn't been selected yet
				//					selectedPeer = p;
				//					RTT = avgRTT;
				//				}
				//				else if (avgRTT < RTT) {	// RTT of new peer is shorter
				//					selectedPeer = p;
				//					RTT = avgRTT;
				//				}
				if (!p.init()) {	// peer returned false during init method which indicates some problem
					System.err.println("Error: Unable to connect to peer " + new String(p.peerID, "UTF-8"));
				}
				else {
					peerList.add(p);
				}
			}
			//			System.out.println("Using peer " + new String(selectedPeer.peerID, "UTF-8") + " with average RTT " + RTT + " ns (" + (RTT / 1000000) + "ms).");
			//			peerList.add(selectedPeer);
		}	// end of if (returnedPeerList != null)
	}

	/**
	 * Gets the average round trip time over ten connections to the peer.
	 * @param p
	 * @return
	 */
	private long getAverageRTT(Peer p)
	{
		long avgRTT = 0;
		List<Long> records = new ArrayList<Long>();
		// attempt multiple pings and add the result to a list
		for (int i = 1; i <= 10; i++)
		{
			try {
				long RTT = p.pingPeer();
				if (RTT == -1) {
					System.err.println("Error: Ping failed.");
				}
				else {
					records.add(RTT);
				}
			}
			catch (IOException ioe)
			{	ioe.printStackTrace();
			}
		}
		// iterate through the list and get the sum
		for (long time : records)
		{
			avgRTT += time;
		}
		// then return the result divided by the number of entries
		return (avgRTT / records.size());
	}

	/**
	 * Attempts to read and parse an expected incoming PeerMessage. If no such Message can
	 * be found, return. Otherwise, perform some action depending on the Message received.
	 * @throws Exception
	 */
	public void decode() throws Exception
	{
		PeerMessage msg;

		if ((msg = messageQueue.take()) != null) {	// some non-null message received
			System.out.println("Decoding message: " + msg.msg);

			switch (msg.msg.id) {
			case (Message.CHOKE_ID):	// peer is choking local host
				msg.peer.isChokedByPeer = true;
			break;
			case (Message.UNCHOKE_ID):	// peer unchoked local host
				msg.peer.isChokedByPeer = false;
			if (msg.peer.isInterestedInPeer == true) {
				msg.peer.sendMessage(msg.peer.getNextRequest());
			}
			break;
			case (Message.INTERESTED_ID):	// peer determined that it wants some piece
				msg.peer.isPeerInterested = true;
			msg.peer.sendMessage(Message.UNCHOKE);
			break;
			case (Message.UNINTERESTED_ID):	// peer determined that it cannot gain any new pieces
				msg.peer.isPeerInterested = false;
			break;
			case (Message.HAVE_ID):	// peer confirming the verification of an earlier-requested piece
				if (msg.peer.bitfield != null) {	// peer has a bitfield that we maintain
					// get the piece index from the message and mark that bitfield's index as true
					msg.peer.bitfield[((Message.Have) msg.msg).index] = true;

					for (int i = 0; i < msg.peer.bitfield.length; i++)
					{
						// detected that the peer has a piece local host doesn't have
						if (msg.peer.bitfield[i] == true && localHostBitfield[i] == false) {
							msg.peer.sendMessage(Message.INTERESTED);
							msg.peer.isInterestedInPeer = true;
							break;
						}
					}	// end of for loop
				}	// end of if (msg.peer.bitfield != null)
			break;
			case (Message.BITFIELD_ID):	// peer provided a bitfield of the pieces it has
				// initialize bitfield by providing the peer's message's bitfield and number of pieces
				boolean[] bitfield = Utils.bitfieldToBooleanArray(((Message.Bitfield) msg.msg).bitfield, info.piece_hashes.length);
			for (int i = 0; i < bitfield.length; i++)
			{
				msg.peer.bitfield[i] = bitfield[i];
			}
			// Determine if this peer has any pieces the local host does not own
			for (int i = 0; i < msg.peer.bitfield.length; i++)
			{
				if (msg.peer.bitfield[i] == true && localHostBitfield[i] == false) {
					msg.peer.sendMessage(Message.INTERESTED);
					msg.peer.isInterestedInPeer = true;
					break;
				}
			}
			break;
			case (Message.PIECE_ID):	// peer sent a piece with some index, offset, and payload
				// Create a new "Have" message to be sent after verifying the hash
				Message.Have haveMsg = new Message.Have(((Message.Piece) msg.msg).index);

			// check if local host's bitfield does not have this piece
			if (!localHostBitfield[((Message.Piece)msg.msg).index]) {
				if (msg.peer.appendToPiece((Message.Piece)msg.msg, info.piece_hashes, this)) {
					localHostBitfield[((Message.Piece)msg.msg).index] = true;

					// TODO Code intended to work for multiple peers, but must check in the future 
					for (Peer peer : peerList)
					{
						try {
							peer.sendMessage(haveMsg);
						}
						catch (IOException ioe) {
							ioe.printStackTrace();
						}
					}	// end of for loop
				}	// end of if (msg.peer.appendToPiece((Message.Piece)msg.msg), info.piece_hashes, this))
			}	// end of if (!localHostBitfield[((Message.Piece)msg.msg).index])
			if (isFileComplete()) {
				// file is done downloading; finish operation of the client
				isDownloading = false;
				tConn.update(TrackerConnection.EVENT_COMPLETED);
				close();	// TODO
				return;
			}
			if (!msg.peer.isChokedByPeer) {
				// as long as the peer who sent the message hasn't choked the local host, try another request
				msg.peer.sendMessage(msg.peer.getNextRequest());
			}
			break;
			}	// end of switch (msg.msg.id)
		}	// end of if ((msg = messageQueue.take()) != null)
		else {	// no messages received
			return;
		}

	}	// end of decode()

	/**
	 * Attempts to read and store incoming Messages by placing any such Message objects into a queue.
	 * @param msg
	 */
	public synchronized void receiveMessage(PeerMessage msg)
	{
		if (msg == null) {
			return;
		}
		messageQueue.add(msg);
	}


	public void setUpload(int upload)
	{
		String up = Integer.toString(upload);

		try {
			File trackerFile = new File(outputFile.getName() + ".stats");
			BufferedWriter out = new BufferedWriter(new FileWriter(trackerFile));
			out.write(up);
			out.close();
		}
		catch (IOException ioe) {
			System.err.println("Could not write upload stats to file.");
			ioe.printStackTrace();
		}
	}

	/**
	 * Sets the download/upload amounts for the tracker.
	 * It retrieves the amount uploaded total from a file.
	 * @throws IOException
	 */
	public void getUpload() throws IOException
	{
		String input;
		File trackerFile = new File(outputFile.getName() + ".stats");
		BufferedReader in = null;

		if (trackerFile.exists()) {
			in = new BufferedReader(new FileReader(trackerFile));
			input = in.readLine();
			TrackerConnection.uploaded = Integer.parseInt(input);
		}
		else {
			TrackerConnection.uploaded = 0;
			TrackerConnection.downloaded = 0;
		}
	}

	/**
	 * Attempts to verify the SHA1 hash of a downloaded piece against the piece's hash in the metainfo.
	 * 
	 * @param piece
	 * @param SHA1hash
	 * @return
	 */
	public static boolean verifySHA1(byte[] piece, ByteBuffer SHA1hash)
	{
		MessageDigest SHA1;
		// get the SHA1 hash for this piece
		try {
			SHA1 = MessageDigest.getInstance("SHA-1");
		}
		catch (NoSuchAlgorithmException nsae) {
			nsae.printStackTrace();
			return false;
		}

		SHA1.update(piece);
		byte[] pieceHash = SHA1.digest();
		// test for equality between the two hashes

		if (Arrays.equals(pieceHash, SHA1hash.array())) {
			return true;
		}

		return false;
	}

	/**
	 * Returns whether the file has no missing pieces by iterating through the bitfield.
	 * 
	 * @return true if bitfield contains all 1's, false otherwise
	 */
	public boolean isFileComplete()
	{
		for (int i = 0; i < localHostBitfield.length; i++)
		{
			if (localHostBitfield[i] == false) {	// bitfield indicates a missing piece
				//				System.out.println("Index " + i + " of " + (localHostBitfield.length-1) + " returned false.");
				return false;
			}
		}
		System.out.println("File complete.");
		System.out.println("Total time to download: " + ((System.nanoTime() - startTime) / 1000000000L) + " seconds.");
		return true;
	}

	/**
	 * Returns an index of a piece that has not been downloaded.
	 * Checks randomly first, then goes in order.
	 * @return
	 */
	public int getNextPieceIndex()
	{
		Random num = new Random();
		int index = num.nextInt(localHostBitfield.length);
		int count = 0;
		while (count < 5)
		{
			if (localHostBitfield[index] == false) {
				return index;
			}
			else {
				index = num.nextInt(localHostBitfield.length);
				count++;
			}
		}
		for (int i = 0; i < localHostBitfield.length; i++)
		{
			if (localHostBitfield[i] == false) {
				return i;
			}
		}

		return -1;
	}


	/**
	 * Writes data to the output file at a specified position and returns true if the piece
	 * checked successfully against the hash and false otherwise. The position is determined by
	 * calculating (pieceLength * index) + offset.
	 * @param pieceMsg
	 * @param SHA1hash
	 * @param data
	 * @return
	 * @throws Exception
	 */
	public boolean updateFile(Message.Piece pieceMsg, ByteBuffer SHA1hash, byte[] data) throws Exception
	{
		if (verifySHA1(data, SHA1hash)) {	// first make sure the piece is validated
			System.out.println("Piece " + pieceMsg.index + " validated.");
			// RandomAccessFile in order to write pieces at arbitrary offsets
			RandomAccessFile raf = new RandomAccessFile(outputFile, "rw");
			// position is the piece's index (the first byte of the piece) + the offset
			//			System.out.println("Seeking to " + (info.piece_length * pieceMsg.index + pieceMsg.offset) + " and writing " + data.length + " bytes.");
			//			raf.seek(info.piece_length * pieceMsg.index + pieceMsg.offset);
			//			System.out.println("Seeking to " + (info.piece_length * pieceMsg.index) + " and writing " + data.length + " bytes.");
			raf.seek(info.piece_length * pieceMsg.index);
			raf.write(data);
			raf.close();
			// update the tracer connection with the amount downloaded
			TrackerConnection.downloaded += data.length;

			return true;
		}
		else {	// piece was not correctly downloaded
			return false;
		}
	}

	/**
	 * Reads the output file at a specified position for pieceLength bytes and returns that
	 * byte array. The position is determined by calculating (pieceLength * index) + offset.
	 * @param index
	 * @param offset
	 * @param pieceLength
	 * @return
	 * @throws IOException
	 */
	public byte[] readFile(int index, int offset, int pieceLength) throws IOException
	{
		// RandomAccessFile in order to retrieve pieces at arbitrary offsets
		RandomAccessFile raf = new RandomAccessFile(outputFile, "r");
		byte[] data = new byte[pieceLength];
		// position is the piece's index (the first byte of the piece) + the offset
		raf.seek(info.piece_length * index + offset);
		raf.readFully(data);
		raf.close();

		return data;
	}

}
