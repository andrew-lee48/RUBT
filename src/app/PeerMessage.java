/*
 * Andrew Lee
 */
package app;

/**
 * PeerMessage.java
 * A simple class to link a Peer to a Message to identify owners of incoming messages.
 */
public class PeerMessage
{
	/** Message object. */
	Message msg;
	/** Peer object who sent the Message. */
	Peer peer;
	
	/**
	 * Constructor for the PeerMessage.
	 * @param peer Peer object
	 * @param msg Message object
	 */
	public PeerMessage(Peer peer, Message msg)
	{
		this.peer = peer;
		this.msg = msg;
	}
	
	/**
	 * Returns the Peer that sent the Message in [Peer]: [Message] format.
	 * @see java.lang.Object#toString()
	 */
	public String toString()
	{
		return peer.toString() + ": " + msg.toString();
	}

}
