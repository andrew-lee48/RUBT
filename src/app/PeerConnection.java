package app;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;

public class PeerConnection extends Thread
{
	/**	Peer object.	*/
	public Peer peer;
	
	/**	Socket for peers to connect to.	*/
	public ServerSocket servSock;
	
	/**	Socket to connect to a peer on.	*/
	public Socket sock;
	
	/**	Port number used to connect.	*/
	public int port;
	
	/**	BitTorrent client.	*/
	TorrentClient client;
	
	/**	Output stream.	*/
	DataOutputStream os;
	
	/**	Input stream.	*/
	DataInputStream is;
	
	/**	Id of the peer.	*/
	byte[] peerId;
	
	/**	The ip address of the peer.	*/
	String ip;
	
	
	/**
	 * Constructor for the PeerConnection object.
	 */
	public PeerConnection(TorrentClient client)
	{
		this.client = client;
		for (int i = 6881; i <= 6889; i++)
		{
			try {
				servSock = new ServerSocket(i);
				System.out.print("Created server socket with peer on port " + i + ".");
			}
			catch (IOException ioe) {
				System.err.println("Error: Could not open ServerSocket on port " + i + ".");
			}
		}
	}
	
	/**
	 * @see java.lang.Thread#run()
	 */
	public void run()
	{
		try {
			sock = servSock.accept();
			is = new DataInputStream(sock.getInputStream());
			os = new DataOutputStream(sock.getOutputStream());
			
			os.write(Peer.generateHandshake(TorrentClient.peerID, client.info.info_hash.array()));
			os.flush();
			
			byte[] response = new byte[68];
			
			sock.setSoTimeout(1000);
			is.readFully(response);
			sock.setSoTimeout(1000);
			
			System.out.println("Handshake response: " + Arrays.toString(response));
			// Parse handshake response to retrieve info
			InetAddress peerIP = sock.getInetAddress();
			ip = peerIP.toString();
			port = sock.getPort();
			peerId = getPeerID(response);
			
			// Create peer and add to list
			peer = new Peer(peerId, port, ip, client);
			peer.init();
			client.peerList.add(peer);
		}
		catch (IOException ioe) {
			ioe.printStackTrace();
		}
	}
	
	
	public byte[] getPeerID(byte[] response)
	{
		byte[] peerID = new byte[20];
		System.arraycopy(response, 40, peerID, 0, 20);
		
		return peerID;
	}
	
}
