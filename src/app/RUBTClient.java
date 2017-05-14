/*
 * Andrew Lee
 */
package app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import GivenTools.BencodingException;
import GivenTools.TorrentInfo;

/**
 * RUBTClient class
 * This class is responsible for parsing command line arguments,
 * reading from a file, and starting the TorrentClient object.
 */
public class RUBTClient
{
	/** Constant that holds the number of nanoseconds in one second. */
	public static final long NANOSEC_PER_SEC = 1000l * 1000 * 1000;

	/** TorrentClient object. */
	public static TorrentClient client;
	/** TorrentInfo object. */
	public static TorrentInfo info;

	/** File object pointing to destination file. */
	public static File outputFile;
	/** File object pointing to the metainfo file. */
	public static File torrentFile;


	/**
	 * Returns a byte array containing the parsed data from the .torrent file.
	 * @param torrentFile
	 * @return
	 */
	private static byte[] getBytesFromFile(File torrentFile)
	{
		byte[] file_bytes = null;
		InputStream file_stream;

		try {
			// Try to open a stream to the metainfo file
			file_stream = new FileInputStream(torrentFile);

			if (!torrentFile.canRead()) {
				System.err.println("Error: The file + " + torrentFile.getName() + " cannot be read.");
			}
			// Prepare to read the file
			file_bytes = new byte[(int)torrentFile.length()];

			int file_offset = 0;
			int bytes_read = 0;

			// Read the file
			while (file_offset < file_bytes.length
					&& (bytes_read = file_stream.read(file_bytes, file_offset, file_bytes.length - file_offset)) >= 0)
			{
				file_offset += bytes_read;
			}

			if (file_offset < file_bytes.length) {
				file_stream.close();
				throw new IOException("Could not completely read file " + torrentFile.getName() + ".");
			}

			file_stream.close();
		}
		catch(FileNotFoundException fnfe)
		{	fnfe.printStackTrace();	}
		catch(IOException ioe)
		{	ioe.printStackTrace();	}

		return file_bytes;
	}

	/**
	 * Main method for RUBTClient.java.
	 * @param args
	 * @throws BencodingException
	 */
	public static void main(String[] args) throws BencodingException
	{
		final String torrentFileName, fileDestName;

		if (args.length != 2) {
			System.err.println("Command line argument structure: [torrent file name] [output file destination]");
			return;
		}
		
		// initialize variables
		torrentFileName = args[0];	fileDestName = args[1];

		torrentFile = new File(torrentFileName);
		outputFile = new File(fileDestName);

		if (!torrentFile.exists()) {
			System.err.println("Error: torrent file not found.");
			return;
		}
		// get the data for the TorrentInfo object
		info = new TorrentInfo(getBytesFromFile(torrentFile));

		if (info == null) {
			System.err.print("Error: TorrentInfo object is null.");
			return;
		}

//		ToolKit.print(info.torrent_file_map);
		// create client
		client = new TorrentClient(info, outputFile);

		boolean[] checkPieces;
//		boolean isLastPieceSmaller = false;	// TODO unused
		// check if the output file already has some pieces in it
		if (outputFile.exists()) {
			if (info.file_length % info.piece_length != 0) {	// last piece's size won't match the others
				checkPieces = new boolean[info.file_length % info.piece_length + 1];
//				isLastPieceSmaller = true;
			}
			else {
				checkPieces = new boolean[info.file_length % info.piece_length];
			}
			// look for already-verified pieces
			try {
				checkPieces = Utils.checkPieces(info, outputFile);
			}
			catch (IOException ioe)
			{	ioe.printStackTrace();	}
			
			client.localHostBitfield = checkPieces;
			
			int count = 0;
			for (int i = 0; i< client.localHostBitfield.length; i++)
			{
				if (client.localHostBitfield[i] == true) {
					count++;
				}
			}
			System.out.println("Number of completed pieces: " + count + " out of " + client.localHostBitfield.length);
			
			// search through the bitfield to determine if there are any missing pieces
			boolean haveFullFile = true;
			for (int i = 0; i < client.localHostBitfield.length; i++)
			{
				if (client.localHostBitfield[i] == false) {
					haveFullFile = false;
					break;
				}
			}

			if (haveFullFile) {
				client.isDownloading = false;
				TorrentClient.haveCompleteFile = true;
			}
			else {
				client.isDownloading = true;
				for (int i = 0; i < checkPieces.length; i++)
				{
					if (checkPieces[i]) {
						TrackerConnection.downloaded += info.piece_length;
					}
				}
			}
		}	// end of if(outputFile.exists())
		else {
			try {
				outputFile.createNewFile();
			}
			catch (IOException ioe)
			{	ioe.printStackTrace();	}
			client.localHostBitfield = new boolean[info.piece_hashes.length];
			Arrays.fill(client.localHostBitfield, false);
		}
		try {
			client.init();
		}
		catch (IOException ioe)
		{	ioe.printStackTrace();	}
		client.isRunning = true;
		client.start();
	}

}
