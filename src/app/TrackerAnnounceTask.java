/*
 * Andrew Lee
 */
package app;

import java.util.TimerTask;

/**
 * TrackerAnnounceTask.java
 * This class is used to send blank announce messages to the tracker
 * after a specified interval in order to remain connected to the tracker.
 * The TorrentClient object that starts this class will restart the timer
 * if it sends some message before the interval has passed.
 */
public class TrackerAnnounceTask extends TimerTask
{
	private TrackerConnection tConn;
	
	private int interval;
	
	boolean isRunning;
	
	
	public TrackerAnnounceTask(TrackerConnection tConn, int interval)
	{
		this.tConn = tConn;
		this.interval = interval;
	}
	
	
	public void run()
	{
		while(isRunning)
		{
			try {
				Thread.sleep(interval * 1000);	// time in milliseconds
			}
			catch (InterruptedException ie)
			{	ie.printStackTrace();	}
//			try {
//				// TODO send a blank announce message to the tracker
//			}
		}
	}

}
