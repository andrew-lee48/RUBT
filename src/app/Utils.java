/*
 * Andrew Lee
 */
package app;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

import GivenTools.ToolKit;
import GivenTools.TorrentInfo;

/**
 * Utils.java
 * This class provides several static methods which are used as utility methods
 * relevant to the operation of a Bitttorr client such as parsing a bitfield byte
 * array into a boolean array and hashing pieces to verify their integrity.
 */
public class Utils extends ToolKit
{
	/** The set of chars that define the hexadecimal values. */
	public static final char[] HEX_CHARS = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};
	
	/**
	 * Converts a given byte array into an escaped hexadecimal string.
	 * 
	 * @param bytes
	 * @return
	 */
	public static String toHexString(byte[] bytes)
	{
		if (bytes == null) {	// null pointer to byte array
			return null;
		}
		
		if (bytes.length == 0) {	// no data
			return "";
		}
		
		StringBuilder sb = new StringBuilder(bytes.length * 3);	// %xx
		
		for (byte b : bytes)
		{
			byte high = (byte) ((b >> 4) & 0x0f);
			byte low  = (byte) (b & 0x0f);
			
			sb.append('%').append(HEX_CHARS[high]).append(HEX_CHARS[low]);
		}
		return sb.toString();
	}
	
	/**
	 * Creates and returns a randomly generated peer ID.
	 * 
	 * @return a 20-byte long alphabetic peer ID guaranteed to start with 'A'
	 */
	protected static byte[] generatePeerID()
	{
		byte[] peerId = new byte[20];
		SecureRandom random = new SecureRandom();
		
		peerId[0] = 'A';
		for (int i = 1; i < peerId.length; i++)
		{
			peerId[i] = (byte)('A' + random.nextInt(26));
		}
		
		return peerId;
	}
	
	/**
	 * Takes a bitfield byte array and returns a boolean array of the same size, initialized according to the bitfield.
	 * 
	 * @param bitfield
	 * @param numPieces
	 * @return
	 */
	public static boolean[] bitfieldToBooleanArray(byte[] bitfield, int numPieces)
	{
		if (bitfield == null) {
			return null;
		}
		boolean[] pieceArray = new boolean[numPieces];
		for (int i = 0; i < pieceArray.length; i++)
		{
			int byteIndex = i / 8;
			int bitIndex = i % 8;
			
			if (( (bitfield[byteIndex] << bitIndex) & 0x80) == 0x80) {	// check for the presence of a 1
				pieceArray[i] = true;
			}
			else {
				pieceArray[i] = false;
			}
		}	// end of for loop
		
		return pieceArray;
	}
	
	/**
	 * Returns a boolean array containing which pieces have been verified as fully downloaded.
	 * 
	 * @param info
	 * @param outputFile
	 * @return
	 * @throws IOException
	 */
	public static boolean[] checkPieces(TorrentInfo info, File outputFile) throws IOException
	{
		System.out.println("Length of file: " + outputFile.length());
		System.out.println("Metainfo file_length: " + info.file_length);
		
		int numPieces = info.piece_hashes.length;
		int pieceLength = info.piece_length;
		int fileLength = info.file_length;
		ByteBuffer[] pieceHashes = info.piece_hashes;
		int lastPieceLength;
		if (fileLength % pieceLength == 0) {
			lastPieceLength = pieceLength;
		}
		else {
			lastPieceLength = fileLength % pieceLength;
		}
		
		byte[] piece = null;
		boolean[] verifiedPieces = new boolean[numPieces];
		
		for (int i = 0; i < numPieces; i++)
		{
			if (i != numPieces - 1) {	// not the last piece
				piece = new byte[pieceLength];
				piece = readFile(i, 0, pieceLength, info, outputFile);
			}
			else {	// it is the last piece
				piece = new byte[lastPieceLength];
				piece = readFile(i, 0, lastPieceLength, info, outputFile);
			}
			// try to verify the SHA1 hash of the piece
			if (TorrentClient.verifySHA1(piece, pieceHashes[i])) {
				verifiedPieces[i] = true;
			}
		}	// end of for loop
		
		return verifiedPieces;
	}
	
	/*
	 * Helper method to read some block of data at a specified piece index and offset.
	 */
	private static byte[] readFile(int index, int offset, int length, TorrentInfo info, File outputFile) throws IOException
	{
		
		RandomAccessFile raf = new RandomAccessFile(outputFile, "r");
		byte[] data = new byte[length];
		
//		System.out.println("Seek to " + (info.piece_length * index + offset + " and read " + length + " bytes."));
		raf.seek(info.piece_length * index + offset);
		raf.read(data);
		raf.close();
		
		return data;
	}

}
