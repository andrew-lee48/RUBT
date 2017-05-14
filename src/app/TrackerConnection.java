/*
 * Andrew Lee
 */
package app;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Timer;

import GivenTools.Bencoder2;
import GivenTools.BencodingException;
import GivenTools.ToolKit;
import GivenTools.TorrentInfo;

/**
 * TrackerConnection class
 * This class connects to the tracker, sends messages in order to
 * maintain a connection, and retrieves the peer list for the Peer object.
 */
public class TrackerConnection
{
	// Event declarations to modify the message sent to the tracker
	public static final String EVENT_STARTED = "started";
	public static final String EVENT_COMPLETED = "completed";
	public static final String EVENT_STOPPED = "stopped";
	public static final String EVENT_EMPTY = "";
	public static final String CHARSET = "UTF-8";
	
	// ByteBuffer keys to access specific relevant information
	public static final ByteBuffer KEY_INTERVAL = ByteBuffer.wrap(new byte[]
			{'i', 'n', 't', 'e', 'r', 'v', 'a', 'l'});
	public static final ByteBuffer KEY_PEERS = ByteBuffer.wrap(new byte[]
			{'p', 'e', 'e', 'r', 's'});
	public static final ByteBuffer KEY_IP = ByteBuffer.wrap(new byte[]
			{'i', 'p'});
	public static final ByteBuffer KEY_PORT = ByteBuffer.wrap(new byte[]
			{'p', 'o', 'r', 't'});
	public static final ByteBuffer KEY_PEER_ID = ByteBuffer.wrap(new byte[]
			{'p', 'e', 'e', 'r', ' ', 'i', 'd'});
	public static final ByteBuffer KEY_FAILURE = ByteBuffer.wrap(new byte[]
			{'f', 'a', 'i', 'l', 'u', 'r', 'e', ' ', 'r', 'e', 'a', 's', 'o', 'n'});
			
	
	public static final int MIN_PORT_RANGE = 6881;
	public static final int MAX_PORT_RANGE = 6889;
	public static final int BYTE_BUFFER = 16384;
	
	public byte[] infohash;
	public byte[] peerID;
	
	public static int downloaded;
	public static int uploaded;
	
	public int left;
	private int port;
	private URL announce;
	private URL requestURL;
	private String event;
	public boolean isRunning = true;
	public int interval;
	
	private TorrentInfo info;
	private TorrentClient client;
	private Map<ByteBuffer, Object> trackerResponse;
	
	public Timer timer;
	public TrackerUpdate trackerUpdate;
	

	public TrackerConnection(TorrentInfo info, final byte[] peerID, int port, TorrentClient client)
	{
		this.infohash = info.info_hash.array();
		this.peerID = peerID;
		this.left = info.file_length;
		this.port = port;
		announce = info.announce_url;
		this.event = EVENT_EMPTY;
		requestURL = getRequestURL(announce);
		
		this.client = client;
	}
	
	
	public ArrayList<Peer> update(String event)
	{
		this.event = event;
		
		if (event.equals(EVENT_STARTED)) {
			downloaded = 0;
			uploaded = 0;
		}
		
		requestURL = getRequestURL(announce);
		
		Map<ByteBuffer, Object> trackerMap = null;
		
		try {
			byte[] trackerResponse = sendGETRequest();
			
			if (trackerResponse == null) {
				System.err.println("Error: no response received from the tracker.");
				return null;
			}
			trackerMap = (Map<ByteBuffer, Object>) Bencoder2.decode(trackerResponse);
		}
		catch (BencodingException be)
		{	be.printStackTrace();	}
		
		if (trackerMap.containsKey(KEY_FAILURE)) {
			System.err.println("Error: Tracker sent a failure message.");
			ByteBuffer obj = (ByteBuffer)trackerMap.get(KEY_FAILURE);
			System.err.println( new String(obj.array()) );
			return null;
		}
		
		ArrayList<Peer> peersList = new ArrayList<Peer>();
		
		interval = (Integer)trackerMap.get(KEY_INTERVAL);
		
		List<Map<ByteBuffer, Object>> peerMappingList = (List<Map<ByteBuffer, Object>>) trackerMap.get(KEY_PEERS);
		
		ToolKit.print(peerMappingList);
		
		if (peerMappingList == null) {
			System.err.println("Error: No peers list given by tracker.");
			return null;
		}
		
		for (Map<ByteBuffer, Object> peer : peerMappingList)
		{
			int peerPort = ((Integer) peer.get(KEY_PORT)).intValue();
			byte[] peerID = ((ByteBuffer) peer.get(KEY_PEER_ID)).array();
			String ip = null;
			try {
				ip = new String(((ByteBuffer) peer.get(KEY_IP)).array(), "ASCII");
			}
			catch (UnsupportedEncodingException uee)
			{	uee.printStackTrace();	}
			
			peersList.add(new Peer(peerID, peerPort, ip, client));
		}
		
		if (interval < 0) {
			interval = 120;	// time in seconds equivalent to two minutes
		}
		
		return peersList;
	}
	
	
	public byte[] sendGETRequest()
	{
		try {
			HttpURLConnection conn = (HttpURLConnection)requestURL.openConnection();
			DataInputStream dis = new DataInputStream(conn.getInputStream());
			
			int nRead = conn.getContentLength();
			byte[] trackerResponse = new byte[nRead];
			
			dis.readFully(trackerResponse);
			dis.close();
			
			return trackerResponse;
		}
		catch (IOException ioe)
		{	ioe.printStackTrace();	}
		return null;
	}

//
//	/*
//	 * Returns a Map containing the tracker's response.
//	 */
//	@SuppressWarnings("unchecked")
//	private Map<ByteBuffer, Object> getTrackerResponse()
//	{
//		System.out.println("Get tracker response");
//		try {
//			int nRead;
//			data = new byte[BYTE_BUFFER];
//			out = new ByteArrayOutputStream();
//
//			while ((nRead = in.read(data, 0, data.length)) != -1)
//			{
//				out.write(data, 0, nRead);
//			}
//			
//			// Parse and decode the Bencoded response from the tracker
//			Map<ByteBuffer, Object> map = (Map<ByteBuffer, Object>)Bencoder2.decode(out.toByteArray());
//			System.out.println("Returning map");
//			return map;
//		}
//		catch (IOException ioe)
//		{	ioe.printStackTrace();	}
//		catch (BencodingException be)
//		{	be.printStackTrace();
//
//		}
//		return null;
//	}

	/*
	 * Returns a URL containing the tracker host and associated parameters.
	 */
	private URL getRequestURL(URL announceURL)
	{	
		String line = announceURL.toString();
		line += "?info_hash=" + Utils.toHexString(infohash);
		line += "&peer_id=" + Utils.toHexString(peerID);
		line += "&port=" + port;
		line += "&uploaded=" + uploaded;
		line += "&downloaded=" + downloaded;
		line += "&left=" + left;
		
		if (event != EVENT_EMPTY) {
			line += "&event=" + event;
		}
		
//		System.out.println("Returning URL: " + line);
		
		try {
			return new URL(line);
		}
		catch (MalformedURLException murle)
		{	murle.printStackTrace();	}
		return null;
	}
	
}
