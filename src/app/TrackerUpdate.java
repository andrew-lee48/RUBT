package app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.TimerTask;

public class TrackerUpdate extends TimerTask
{
	/**	The tracker.	*/
	TrackerConnection tConn;
	
	/**	The Torrent client.	*/
	TorrentClient client;
	
	/**
	 * Constructor for the TrackerUpdate object.
	 * 
	 * @param tConn
	 * @param client
	 */
	TrackerUpdate(TrackerConnection tConn, TorrentClient client)
	{
		this.tConn = tConn;
		this.client = client;
	}
	
	/**
	 * @see java.util.TimerTask#run()
	 */
	public void run()
	{
		// Get a new list of peers from the tracker
		ArrayList<Peer> peers = tConn.update("");
		boolean isAlreadyPeer = false;
		
		// 
		for (Peer p1 : peers)
		{
			for (Peer p2 : client.peerList)
			{
				if (Arrays.equals(p1.peerID, p2.peerID)) {
					isAlreadyPeer = true;
					break;
				}
			}
			if (isAlreadyPeer == false) {
				client.peerList.add(p1);
			}
			else {
				isAlreadyPeer = false;
			}
		}
	}

}
