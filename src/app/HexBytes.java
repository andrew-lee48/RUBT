/*
 * Andrew Lee
 */
package app;

/**
 * HexBytes.java
 * This class provides a utility method to convert a byte array
 * into a hexadecimal string.
 */
public class HexBytes
{
	/** The characters that represent hexadecimal values. */
	final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();
	
	/**
	 * Returns a String containing the hexadecimal representation of an array of bytes.
	 * @param bytes
	 * @return
	 */
	public static String bytesToHex(byte[] bytes) {
		char[] hexChars = new char[bytes.length * 2];
		for ( int j = 0; j < bytes.length; j++ ) {
			int v = bytes[j] & 0xFF;
			hexChars[j * 2] = hexArray[v >>> 4];
			hexChars[j * 2 + 1] = hexArray[v & 0x0F];
		}
		return new String(hexChars);
	}
}
