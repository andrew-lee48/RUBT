/*
 * Andrew Lee
 */
package app;

import java.io.IOException;

/**
 * PeerKeepAliveTask.java
 * This class runs a thread that after sleeping for some interval,
 * prompts the TorrentClient to send a keep-alive message to
 * the peer to maintain the connection. Other classes will prompt
 * the thread to interrupt and start again if another message is
 * sent before then.
 */
public class PeerKeepAliveTask extends Thread
{
	/** Peer connection linked to this thread. */
	private Peer peer;
	/** Interval that the thread should sleep before sending the keep-alive message. */
	private int interval = 120 * 1000;	// 120 seconds in milliseconds
	/** Determines if the thread is running or not. */
	boolean isRunning = false;
	
	/**
	 * Constructor for the PeerKeepAliveTask class
	 * 
	 * @param peer
	 */
	public PeerKeepAliveTask(Peer peer)
	{
		this.peer = peer;
	}
	
	/**
	 * @see java.lang.Thread#run()
	 */
	public void run()
	{
		while (isRunning)
		{
			try {
				Thread.sleep(interval);
			}
			catch (InterruptedException ie)
			{	ie.printStackTrace();	}
			try {
				peer.sendMessage(Message.KEEP_ALIVE);
			}
			catch (IOException ioe)
			{	ioe.printStackTrace();	}
		}	// end of while (isRunning)
	}

}
