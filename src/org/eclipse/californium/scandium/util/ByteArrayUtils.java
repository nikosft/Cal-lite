/*******************************************************************************
 * Copyright (c) 2014 Institute for Pervasive Computing, ETH Zurich and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *    Matthias Kovatsch - creator and main architect
 *    Stefan Jucker - DTLS implementation
 ******************************************************************************/
package org.eclipse.californium.scandium.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ByteArrayUtils {

	/**
	 * Adds a padding to the given array, such that a new array with the given
	 * length is generated.
	 * 
	 * @param array
	 *            the array to be padded.
	 * @param value
	 *            the padding value.
	 * @param newLength
	 *            the new length of the padded array.
	 * @return the array padded with the given value.
	 */
	public static byte[] padArray(byte[] array, byte value, int newLength) {
		int length = array.length;
		int paddingLength = newLength - length;

		if (paddingLength < 1) {
			return array;
		} else {
			byte[] padding = new byte[paddingLength];
			Arrays.fill(padding, value);

			return concatenate(array, padding);
		}

	}

	/**
	 * Truncates the given array to the request length.
	 * 
	 * @param array
	 *            the array to be truncated.
	 * @param newLength
	 *            the new length in bytes.
	 * @return the truncated array.
	 */
	public static byte[] truncate(byte[] array, int newLength) {
		if (array.length < newLength) {
			return array;
		} else {
			byte[] truncated = new byte[newLength];
			System.arraycopy(array, 0, truncated, 0, newLength);

			return truncated;
		}
	}

	/**
	 * Concatenates two byte arrays.
	 * 
	 * @param a
	 *            the first array.
	 * @param b
	 *            the second array.
	 * @return the concatenated array.
	 */
	public static byte[] concatenate(byte[] a, byte[] b) {
		int lengthA = a.length;
		int lengthB = b.length;

		byte[] concat = new byte[lengthA + lengthB];

		System.arraycopy(a, 0, concat, 0, lengthA);
		System.arraycopy(b, 0, concat, lengthA, lengthB);

		return concat;
	}

	/**
	 * Computes array-wise XOR.
	 * 
	 * @param a
	 *            the first array.
	 * @param b
	 *            the second array.
	 * @return the XOR-ed array.
	 */
	public static byte[] xorArrays(byte[] a, byte[] b) {
		byte[] xor = new byte[a.length];

		for (int i = 0; i < a.length; i++) {
			xor[i] = (byte) (a[i] ^ b[i]);
		}

		return xor;
	}

	/**
	 * Splits the given array into blocks of given size and adds padding to the
	 * last one, if necessary.
	 * 
	 * @param byteArray
	 *            the array.
	 * @param blocksize
	 *            the block size.
	 * @return a list of blocks of given size.
	 */
	public static List<byte[]> splitAndPad(byte[] byteArray, int blocksize) {
		List<byte[]> blocks = new ArrayList<byte[]>();
		int numBlocks = (int) Math.ceil(byteArray.length / (double) blocksize);

		for (int i = 0; i < numBlocks; i++) {

			byte[] block = new byte[blocksize];
			Arrays.fill(block, (byte) 0x00);
			if (i + 1 == numBlocks) {
				// the last block
				int remainingBytes = byteArray.length - (i * blocksize);
				System.arraycopy(byteArray, i * blocksize, block, 0, remainingBytes);
			} else {
				System.arraycopy(byteArray, i * blocksize, block, 0, blocksize);
			}
			blocks.add(block);
		}

		return blocks;
	}

	/**
	 * Takes a byte array and returns it HEX representation.
	 * 
	 * @param byteArray
	 *            the byte array.
	 * @return the HEX representation.
	 */
	public static String toHexString(byte[] byteArray) {

		if (byteArray != null && byteArray.length != 0) {

			StringBuilder builder = new StringBuilder(byteArray.length * 3);
			for (int i = 0; i < byteArray.length; i++) {
				builder.append(String.format("%02X", 0xFF & byteArray[i]));

				if (i < byteArray.length - 1) {
					builder.append(' ');
				}
			}
			return builder.toString();
		} else {
			return "--";
		}
	}
        
        /**
	 * Takes a byte array and returns it Base64 representation.
	 * 
	 * @param src
	 *            the byte array.
	 * @return the Base64 representation.
	 */
	public static String toBase64String(byte[] src) {
            int MIMELINEMAX = 76;
            byte[] CRLF = new byte[] {'\r', '\n'};
            char[] toBase64 = {
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
            'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
            'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
            'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '+', '/'
            };
            
            int linemax = MIMELINEMAX;
            byte[] newline = CRLF;
            boolean doPadding = true;
            
            int len = 0;
            int  srclen = src.length;
            len = 4 * ((srclen + 2) / 3);
            //line seperators
            len += (len - 1) / linemax * newline.length;            
            byte[] dst = new byte[len];
            char[] base64 = toBase64;
            int off = 0;
            int end = src.length;
            int sp = off;
            int slen = (end - off) / 3 * 3;
            int sl = off + slen;
            if (linemax > 0 && slen  > linemax / 4 * 3)
                slen = linemax / 4 * 3;
            int dp = 0;
            while (sp < sl) {
                int sl0 = Math.min(sp + slen, sl);
                for (int sp0 = sp, dp0 = dp ; sp0 < sl0; ) {
                    int bits = (src[sp0++] & 0xff) << 16 |
                               (src[sp0++] & 0xff) <<  8 |
                               (src[sp0++] & 0xff);
                    dst[dp0++] = (byte)base64[(bits >>> 18) & 0x3f];
                    dst[dp0++] = (byte)base64[(bits >>> 12) & 0x3f];
                    dst[dp0++] = (byte)base64[(bits >>> 6)  & 0x3f];
                    dst[dp0++] = (byte)base64[bits & 0x3f];
                }
                int dlen = (sl0 - sp) / 3 * 4;
                dp += dlen;
                sp = sl0;
                if (dlen == linemax && sp < end) {
                    for (byte b : newline){
                        dst[dp++] = b;
                    }
                }
            }
            if (sp < end) {               // 1 or 2 leftover bytes
                int b0 = src[sp++] & 0xff;
                dst[dp++] = (byte)base64[b0 >> 2];
                if (sp == end) {
                    dst[dp++] = (byte)base64[(b0 << 4) & 0x3f];
                    if (doPadding) {
                        dst[dp++] = '=';
                        dst[dp++] = '=';
                    }
                } else {
                    int b1 = src[sp++] & 0xff;
                    dst[dp++] = (byte)base64[(b0 << 4) & 0x3f | (b1 >> 4)];
                    dst[dp++] = (byte)base64[(b1 << 2) & 0x3f];
                    if (doPadding) {
                        dst[dp++] = '=';
                    }
                }
            }
            int ret =  dp;
            byte[] encoded;
            if (ret != dst.length)
                 encoded = Arrays.copyOf(dst, ret);
            encoded = dst;
            return new String(encoded, 0, 0, encoded.length);
	}

	/**
	 * Takes a HEX stream and returns the corresponding byte array.
	 * 
	 * @param hexStream
	 *            the HEX stream.
	 * @return the byte array.
	 */
	public static byte[] hexStreamToByteArray(String hexStream) {
		int length = hexStream.length();

		byte[] data = new byte[length / 2];
		for (int i = 0; i < length; i += 2) {
			data[i / 2] = (byte) ((Character.digit(hexStream.charAt(i), 16) << 4) + Character.digit(hexStream.charAt(i + 1), 16));
		}
		return data;
	}
	
	/**
	 * Trims the leading zeros.
	 * 
	 * @param byeArray the byte array with possible leading zeros.
	 * @return the byte array with no leading zeros.
	 */
	public static byte[] trimZeroes(byte[] byeArray) {
		// count how many leading zeros
		int count = 0;
		while ((count < byeArray.length - 1) && (byeArray[count] == 0)) {
			count++;
		}
		if (count == 0) {
			// no leading zeros initially
			return byeArray;
		}
		byte[] trimmedByteArray = new byte[byeArray.length - count];
		System.arraycopy(byeArray, count, trimmedByteArray, 0, trimmedByteArray.length);
		return trimmedByteArray;
	}
}
