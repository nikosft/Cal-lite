/*******************************************************************************
 * Copyright (c) 2014, 2015 Institute for Pervasive Computing, ETH Zurich and others.
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
 *    Kai Hudalla (Bosch Software Innovations GmbH) - expose sequence number as
 *                   property of type long in order to prevent tedious conversions
 *                   in client code
 *    Kai Hudalla (Bosch Software Innovations GmbH) - add initial support for Block Ciphers
 ******************************************************************************/
package org.eclipse.californium.scandium.dtls;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;

import org.eclipse.californium.scandium.dtls.cipher.CCMBlockCipher;
import org.eclipse.californium.scandium.dtls.cipher.CipherSuite;
import org.eclipse.californium.scandium.dtls.cipher.CipherSuite.CipherType;
import org.eclipse.californium.scandium.dtls.cipher.CipherSuite.KeyExchangeAlgorithm;
import org.eclipse.californium.scandium.dtls.cipher.InvalidMacException;
import org.eclipse.californium.scandium.util.ByteArrayUtils;
import org.eclipse.californium.scandium.util.DatagramReader;
import org.eclipse.californium.scandium.util.DatagramWriter;



public class Record {

	// Logging ////////////////////////////////////////////////////////

	protected static final Logger LOGGER = Logger.getLogger(Record.class.getCanonicalName());

	// CoAP-specific constants/////////////////////////////////////////

	private static final int CONTENT_TYPE_BITS = 8;

	private static final int VERSION_BITS = 8; // for major and minor each

	private static final int EPOCH_BITS = 16;

	private static final int SEQUENCE_NUMBER_BITS = 48;

	private static final int LENGTH_BITS = 16;

	private static final long MAX_SEQUENCE_NO = 281474976710655L; // 2^48 - 1;
	
	// Members ////////////////////////////////////////////////////////

	/** The higher-level protocol used to process the enclosed fragment */
	private ContentType type = null;

	/**
	 * The version of the protocol being employed. DTLS version 1.2 uses { 254,
	 * 253 }
	 */
	private ProtocolVersion version = new ProtocolVersion();

	/** A counter value that is incremented on every cipher state change */
	private int epoch = -1;

	/** The sequence number for this record */
	private long sequenceNumber;

	/** The length (in bytes) of the following {@link DTLSMessage}. */
	private int length = 0;

	/**
	 * The application data. This data is transparent and treated as an
	 * independent block to be dealt with by the higher-level protocol specified
	 * by the type field.
	 */
	private DTLSMessage fragment = null;

	/** The raw byte representation of the fragment. */
	private byte[] fragmentBytes = null;

	/** The DTLS session. */
	private DTLSSession session;

	// Constructors ///////////////////////////////////////////////////

	/**
	 * Creates a record from a <em>DTLSCiphertext</em> struct received from the network.
	 * 
	 * Called when reconstructing the record from a byte array. The fragment
	 * will remain in its binary representation up to the DTLS Layer.
	 * 
	 * @param type the content type
	 * @param version the version
	 * @param epoch the epoch
	 * @param sequenceNumber the sequence number
	 * @param fragmentBytes the encrypted data
	 */
	public Record(ContentType type, ProtocolVersion version, int epoch, long sequenceNumber, byte[] fragmentBytes) {
		this.type = type;
		this.version = version;
		this.epoch = epoch;
		this.sequenceNumber = sequenceNumber;
		this.fragmentBytes = fragmentBytes;
		this.length = fragmentBytes.length;
	}

	/**
	 * Creates a record representing a <em>DTLSPlaintext</em> struct based on a {@link DTLSMessage}.
	 * 
	 * @param type
	 *            the type
	 * @param epoch
	 *            the epoch
	 * @param sequenceNumber
	 *            the 48-bit sequence number
	 * @param fragment
	 *            the fragment
	 * @param session
	 *            the session
	 * @throws IllegalArgumentException if the given sequence number is longer than 48 bits
	 * @throws GeneralSecurityException if the message could not be encrypted, e.g.
	 *            because the JVM does not support the negotiated cipher suite's cipher algorithm
	 */

	public Record(ContentType type, int epoch, long sequenceNumber, DTLSMessage fragment, DTLSSession session) 
		throws IllegalArgumentException, GeneralSecurityException {
		if (sequenceNumber > MAX_SEQUENCE_NO) {
			throw new IllegalArgumentException("Sequence number must be 48 bits only");
		}
		this.type = type;
		this.epoch = epoch;
		this.sequenceNumber = sequenceNumber;
		this.session = session;
		setFragment(fragment);
	}

	// Serialization //////////////////////////////////////////////////

	/**
	 * Encodes the DTLS Record into its raw binary structure as defined in the
	 * DTLS v.1.2 specification.
	 * 
	 * @return the encoded byte array
	 */
	public synchronized byte[] toByteArray() throws GeneralSecurityException {
		DatagramWriter writer = new DatagramWriter();

		writer.write(type.getCode(), CONTENT_TYPE_BITS);

		writer.write(version.getMajor(), VERSION_BITS);
		writer.write(version.getMinor(), VERSION_BITS);

		writer.write(epoch, EPOCH_BITS);
		writer.writeLong(sequenceNumber, SEQUENCE_NUMBER_BITS);

		length = fragmentBytes.length;
		writer.write(length, LENGTH_BITS);

		writer.writeBytes(fragmentBytes);

		return writer.toByteArray();
	}

	/**
	 * Parses raw binary representations of DTLS records into an object representation.
	 * 
	 * The binary representation is expected to comply with the structure defined
	 * in <a href="http://tools.ietf.org/html/rfc6347#section-4.3.1">RFC6347 - DTLS</a>.
	 * 
	 * @param byteArray the raw binary representation containing one or more DTLS records
	 * @return the object representations of the DTLS records
	 */
	public static List<Record> fromByteArray(byte[] byteArray) {
		List<Record> records = new ArrayList<Record>();
		
		DatagramReader reader = new DatagramReader(byteArray);
		
		while (reader.bytesAvailable()) {

			int type = reader.read(CONTENT_TYPE_BITS);
			int major = reader.read(VERSION_BITS);
			int minor = reader.read(VERSION_BITS);
			ProtocolVersion version = new ProtocolVersion(major, minor);
	
			int epoch = reader.read(EPOCH_BITS);
			long sequenceNumber = reader.readLong(SEQUENCE_NUMBER_BITS);
	
			int length = reader.read(LENGTH_BITS);
	
			// delay decryption/interpretation of fragment
			byte[] fragmentBytes = reader.readBytes(length);
	
			ContentType contentType = ContentType.getTypeByValue(type);
			if (contentType == null) {
				LOGGER.log(Level.FINE, "Received DTLS record of unsupported type [{0}]. Discarding ...", type);
			} else {
				records.add(new Record(contentType, version, epoch, sequenceNumber, fragmentBytes));
			}	
		}
		
		return records;
	}

	// Cryptography /////////////////////////////////////////////////////////

	/**
	 * Encrypts a TLSPlaintext.fragment according to the <em>current</em> DTLS connection state.
	 * 
	 * @param plaintextFragment
	 *            the TLSPlaintext.fragment to encrypt
	 * @return the (encrypted) TLSCiphertext.fragment
	 * @throws GeneralSecurityException if the plaintext could not be encrypted, e.g.
	 *            because the JVM does not support the negotiated cipher suite's cipher algorithm
	 */
	private byte[] encryptFragment(byte[] plaintextFragment) throws GeneralSecurityException {
		
		if (session == null) {
			return plaintextFragment;
		}

		byte[] encryptedFragment = plaintextFragment;

		CipherSuite cipherSuite = session.getWriteState().getCipherSuite();
		LOGGER.log(Level.FINER, "Encrypting record fragment using current write state\n{0}", session.getWriteState());
		
		switch (cipherSuite.getCipherType()) {
		case NULL:
			// do nothing
			break;
			
		case AEAD:
			encryptedFragment = encryptAEAD(plaintextFragment);
			break;
			
		case BLOCK:
			encryptedFragment = encryptBlockCipher(plaintextFragment);
			break;
			
		case STREAM:
			// Currently, Scandium does not support any stream ciphers
			// RC4 is explicitly ruled out from being used in DTLS
			// see http://tools.ietf.org/html/rfc6347#section-4.1.2.2
			break;

		default:
			break;
		}

		return encryptedFragment;
	}

	/**
	 * Decrypts a TLSCiphertext.fragment according to the <em>current</em> DTLS connection state.
	 * 
	 * So, potentially no decryption takes place at all.
	 * 
	 * @param ciphertextFragment
	 *            the TLSCiphertext.fragment to decrypt
	 * @return the (de-crypted) TLSPlaintext.fragment
	 * @throws GeneralSecurityException
	 *             if decryption fails, e.g. because the MAC could not be validated.
	 */
	private byte[] decryptFragment(byte[] ciphertextFragment) throws GeneralSecurityException {
		if (session == null) {
			return ciphertextFragment;
		}

		byte[] fragment = ciphertextFragment;

		CipherSuite cipherSuite = session.getReadState().getCipherSuite();
		LOGGER.log(Level.FINER, "Decrypting record fragment using current read state\n{0}", session.getReadState());
		
		switch (cipherSuite.getCipherType()) {
		case NULL:
			// do nothing
			break;
			
		case AEAD:
			fragment = decryptAEAD(ciphertextFragment);
			break;
			
		case BLOCK:
			fragment = decryptBlockCipher(ciphertextFragment);
			break;
			
		case STREAM:
			// Currently, Scandium does not support any stream ciphers
			// RC4 is explicitly ruled out from being used in DTLS
			// see http://tools.ietf.org/html/rfc6347#section-4.1.2.2
			break;

		default:
			break;
		}

		return fragment;
	}
	
	// Block Cipher Cryptography //////////////////////////////////////
	
	/**
	 * Converts a given TLSCompressed.fragment to a
	 * TLSCiphertext.fragment structure as defined by
	 * <a href="http://tools.ietf.org/html/rfc5246#section-6.2.3.2">
	 * RFC 5246, section 6.2.3.2</a>
	 * 
	 * <pre>
	 * struct {
	 *    opaque IV[SecurityParameters.record_iv_length];
	 *    block-ciphered struct {
	 *       opaque content[TLSCompressed.length];
	 *       opaque MAC[SecurityParameters.mac_length];
	 *       uint8 padding[GenericBlockCipher.padding_length];
	 *       uint8 padding_length;
	 *    };
	 * } GenericBlockCipher;
	 * </pre>
	 * 
	 * The particular cipher to use is determined from the negotiated
	 * cipher suite in the <em>current</em> DTLS connection state.
	 *  
	 * @param compressedFragment the TLSCompressed.fragment
	 * @return the TLSCiphertext.fragment
	 * @throws NullPointerException if the given fragment is <code>null</code>
	 * @throws IllegalStateException if the {@link #session} is not
	 * @throws GeneralSecurityException if the JVM does not support the negotiated block cipher
	 */
	protected synchronized final byte[] encryptBlockCipher(byte[] compressedFragment) throws GeneralSecurityException {
		if (session == null) {
			throw new IllegalStateException("DTLS session must be set on record");
		} else if (compressedFragment == null) {
			throw new NullPointerException("Compressed fragment must not be null");
		}
		
		/*
		 * See http://tools.ietf.org/html/rfc5246#section-6.2.3.2 for
		 * explanation
		 */
		DatagramWriter plaintext = new DatagramWriter();
		plaintext.writeBytes(compressedFragment);
		
		// add MAC
		plaintext.writeBytes(getBlockCipherMac(session.getWriteState(), compressedFragment));
				
		// determine padding length
		int length = compressedFragment.length + session.getWriteState().getCipherSuite().getMacLength() + 1;
		int smallestMultipleOfBlocksize = session.getWriteState().getRecordIvLength();
		while ( smallestMultipleOfBlocksize <= length) {
			smallestMultipleOfBlocksize += session.getWriteState().getRecordIvLength();
		}
		int paddingLength = smallestMultipleOfBlocksize % length;
		
		// create padding
		byte[] padding = new byte[paddingLength + 1];
		Arrays.fill(padding, (byte) paddingLength);
		plaintext.writeBytes(padding);
					
		// TODO: check if we can re-use the cipher instance
		Cipher blockCipher = Cipher.getInstance(session.getWriteState().getCipherSuite().getTransformation());
		blockCipher.init(Cipher.ENCRYPT_MODE,
				session.getWriteState().getEncryptionKey());

		// create GenericBlockCipher structure
		DatagramWriter result = new DatagramWriter();
		result.writeBytes(blockCipher.getIV());
		result.writeBytes(blockCipher.doFinal(plaintext.toByteArray()));
		return result.toByteArray();
	}
	
	/**
	 * Converts a given TLSCiphertext.fragment to a
	 * TLSCompressed.fragment structure as defined by
	 * <a href="">RFC 5246, section 6.2.3.2</a>:
	 * 
	 * <pre>
	 * struct {
	 *    opaque IV[SecurityParameters.record_iv_length];
	 *    block-ciphered struct {
	 *       opaque content[TLSCompressed.length];
	 *       opaque MAC[SecurityParameters.mac_length];
	 *       uint8 padding[GenericBlockCipher.padding_length];
	 *       uint8 padding_length;
	 *    };
	 * } GenericBlockCipher;
	 * </pre>
	 * 
	 * The particular cipher to use is determined from the negotiated
	 * cipher suite in the <em>current</em> DTLS connection state.
	 * 
	 * @param ciphertextFragment the TLSCiphertext.fragment
	 * @return the TLSCompressed.fragment
	 * @throws NullPointerException if the given ciphertext is <code>null</code>
	 * @throws IllegalStateException if the {@link #session} is not set
	 * @throws InvalidMacException if message authentication failed
	 * @throws GeneralSecurityException if the ciphertext could not be decrpyted, e.g.
	 *             because the JVM does not support the negotiated block cipher
	 */
	protected final byte[] decryptBlockCipher(byte[] ciphertextFragment) throws GeneralSecurityException {
		if (session == null) {
			throw new IllegalStateException("DTLS session must be set on record");
		} else if (ciphertextFragment == null) {
			throw new NullPointerException("Ciphertext must not be null");
		}
		/*
		 * See http://tools.ietf.org/html/rfc5246#section-6.2.3.2 for
		 * explanation
		 */
		DatagramReader reader = new DatagramReader(ciphertextFragment);
		byte[] iv = reader.readBytes(session.getReadState().getRecordIvLength());
		// TODO: check if we can re-use the cipher instance
		Cipher blockCipher = Cipher.getInstance(session.getReadState().getCipherSuite().getTransformation());
		blockCipher.init(Cipher.DECRYPT_MODE,
				session.getReadState().getEncryptionKey(),
				new IvParameterSpec(iv));
		byte[] plaintext = blockCipher.doFinal(reader.readBytesLeft());
		// last byte contains padding length
		int paddingLength = plaintext[plaintext.length - 1];
		int fragmentLength = plaintext.length
				- 1 // paddingLength byte
				- paddingLength
				- session.getReadState().getCipherSuite().getMacLength();
		
		reader = new DatagramReader(plaintext);
		byte[] content = reader.readBytes(fragmentLength);			
		byte[] macFromMessage = reader.readBytes(session.getReadState().getCipherSuite().getMacLength());
		byte[] mac = getBlockCipherMac(session.getReadState(), content);
		if (Arrays.equals(macFromMessage, mac)) {
			return content;
		} else {
			throw new InvalidMacException(mac, macFromMessage);
		}
	}
	
	/**
	 * Calculates a MAC for use with CBC block ciphers as specified
	 * by <a href="http://tools.ietf.org/html/rfc5246#section-6.2.3.2">
	 * RFC 5246, section 6.2.3.2</a>.
	 * 
	 * @param conState the security parameters for calculating the MAC
	 * @param content the data to calculate the MAC for
	 * @return the MAC
	 * @throws GeneralSecurityException if the MAC could not be calculated,
	 *           e.g. because the JVM does not support the cipher suite's
	 *           HMac algorithm
	 */
	private byte[] getBlockCipherMac(DTLSConnectionState conState, byte[] content) throws GeneralSecurityException {
		
		Mac hmac = Mac.getInstance(conState.getCipherSuite().getMacName());
		hmac.init(conState.getMacKey());
		
		DatagramWriter mac = new DatagramWriter();
		mac.writeBytes(generateAdditionalData(content.length));
		mac.writeBytes(content);
		return hmac.doFinal(mac.toByteArray());
	}
	
	// AEAD Cryptography //////////////////////////////////////////////
	
	protected byte[] encryptAEAD(byte[] byteArray) throws GeneralSecurityException {
		/*
		 * See http://tools.ietf.org/html/rfc5246#section-6.2.3.3 for
		 * explanation of additional data or
		 * http://tools.ietf.org/html/rfc5116#section-2.1
		 */
		byte[] iv = session.getWriteState().getIv().getIV();
		byte[] nonce = generateNonce(iv);
		byte[] key = session.getWriteState().getEncryptionKey().getEncoded();
//		byte[] additionalData = generateAdditionalData(getLength());
		byte[] additionalData = generateAdditionalData(byteArray.length);
		
		byte[] encryptedFragment = CCMBlockCipher.encrypt(key, nonce, additionalData, byteArray, 8);
		
		/*
		 * Prepend the explicit nonce as specified in
		 * http://tools.ietf.org/html/rfc5246#section-6.2.3.3 and
		 * http://tools.ietf.org/html/draft-mcgrew-tls-aes-ccm-04#section-3
		 */
		byte[] explicitNonce = generateExplicitNonce();
		encryptedFragment = ByteArrayUtils.concatenate(explicitNonce, encryptedFragment);
		
		return encryptedFragment;
	}
	
	/**
	 * Decrypts the given byte array using a AEAD cipher.
	 * 
	 * @param byteArray the encrypted message
	 * @return the decrypted message
	 * @throws InvalidMacException if message authentication failed
	 * @throws GeneralSecurityException if de-cryption failed
	 */
	protected byte[] decryptAEAD(byte[] byteArray) throws GeneralSecurityException {
		/*
		 */
		
		// the "implicit" part of the nonce is the salt as exchanged during the session establishment
		byte[] iv = session.getReadState().getIv().getIV();
		// the symmetric key exchanged during the DTLS handshake
		byte[] key = session.getReadState().getEncryptionKey().getEncoded();
		/*
		 * See http://tools.ietf.org/html/rfc5246#section-6.2.3.3 and
		 * http://tools.ietf.org/html/rfc5116#section-2.1 for an
		 * explanation of "additional data" and its structure
		 * 
		 * The decrypted message is always 16 bytes shorter than the cipher (8
		 * for the authentication tag and 8 for the explicit nonce).
		 */
//		byte[] additionalData = generateAdditionalData(getLength() - 16);
		byte[] additionalData = generateAdditionalData(byteArray.length - 16);

		DatagramReader reader = new DatagramReader(byteArray);
		
		// create explicit nonce from values provided in DTLS record 
		byte[] explicitNonce = generateExplicitNonce();
		// retrieve actual explicit nonce as contained in GenericAEADCipher struct (8 bytes long)
		byte[] explicitNonceUsed = reader.readBytes(8);
		if (!Arrays.equals(explicitNonce, explicitNonceUsed) && LOGGER.isLoggable(Level.FINE)) {
			StringBuffer b = new StringBuffer("The explicit nonce used by the sender does not match the values provided in the DTLS record");
			b.append("\nUsed    : ").append(ByteArrayUtils.toHexString(explicitNonceUsed));
			b.append("\nExpected: ").append(ByteArrayUtils.toHexString(explicitNonce));
			LOGGER.log(Level.FINE, b.toString());
		}

		byte[] nonce = getNonce(iv, explicitNonceUsed);
		byte[] decrypted = CCMBlockCipher.decrypt(key, nonce, additionalData, reader.readBytesLeft(), 8);

		return decrypted;
	}
	
	// Cryptography Helper Methods ////////////////////////////////////

	/**
	 * http://tools.ietf.org/html/draft-mcgrew-tls-aes-ccm-ecc-03#section-2:
	 * 
	 * <pre>
	 * struct {
	 *   case client:
	 *     uint32 client_write_IV;  // low order 32-bits
	 *   case server:
	 *     uint32 server_write_IV;  // low order 32-bits
	 *  uint64 seq_num;
	 * } CCMNonce.
	 * </pre>
	 * 
	 * @param iv
	 *            the write IV (either client or server).
	 * @return the 12 bytes nonce.
	 */
	private byte[] generateNonce(byte[] iv) {
		return getNonce(iv, generateExplicitNonce());
	}

	private byte[] getNonce(byte[] implicitNonce, byte[] explicitNonce) {
		DatagramWriter writer = new DatagramWriter();
		
		writer.writeBytes(implicitNonce);
		writer.writeBytes(explicitNonce);
		
		return writer.toByteArray();
	}

	
	/**
	 * Generates the explicit part of the nonce to be used with the AEAD Cipher.
	 * 
	 * <a href="http://tools.ietf.org/html/rfc6655#section-3">RFC6655, Section 3</a>
	 * encourages the use of the session's 16bit epoch value concatenated
	 * with a monotonically increasing 48bit sequence number as the explicit nonce. 
	 * 
	 * @return the 64-bit explicit nonce constructed from the epoch and sequence number
	 */
	private byte[] generateExplicitNonce() {
		
		//TODO: re-factor to use simple byte array manipulation instead of using bit-based DatagramWriter
		DatagramWriter writer = new DatagramWriter();
		
		writer.write(epoch, EPOCH_BITS);
		writer.writeLong(sequenceNumber, SEQUENCE_NUMBER_BITS);
		
		return writer.toByteArray();
	}

	/**
	 * See <a href="http://tools.ietf.org/html/rfc5246#section-6.2.3.3">RFC
	 * 5246</a>:
	 * 
	 * <pre>
	 * additional_data = seq_num + TLSCompressed.type +
	 * TLSCompressed.version + TLSCompressed.length;
	 * </pre>
	 * 
	 * where "+" denotes concatenation.
	 * 
	 * @return the additional authentication data.
	 */
	private byte[] generateAdditionalData(int length) {
		DatagramWriter writer = new DatagramWriter();
		
		writer.write(epoch, EPOCH_BITS);
		writer.writeLong(sequenceNumber, SEQUENCE_NUMBER_BITS);

		writer.write(type.getCode(), CONTENT_TYPE_BITS);

		writer.write(version.getMajor(), VERSION_BITS);
		writer.write(version.getMinor(), VERSION_BITS);
		
		writer.write(length, LENGTH_BITS);

		return writer.toByteArray();
	}

	// Getters and Setters ////////////////////////////////////////////

	public ContentType getType() {
		return type;
	}

//	public void setType(ContentType type) {
//		this.type = type;
//	}
//
	public ProtocolVersion getVersion() {
		return version;
	}

//	public void setVersion(ProtocolVersion version) {
//		this.version = version;
//	}
//
	public int getEpoch() {
		return epoch;
	}

//	public void setEpoch(int epoch) {
//		this.epoch = epoch;
//	}
//
	public long getSequenceNumber() {
		return sequenceNumber;
	}

	/**
	 * Sets the record's sequence number.
	 * 
	 * This is primarily intended for cases where the record needs to be re-transmitted
	 * with a new sequence number.
	 * 
	 * This method also takes care of re-encrypting the record's message fragment if
	 * a CBC block cipher is used in order to update the ciphertext's MAC which is
	 * parameterized with the record's sequence number.
	 *  
	 * @param sequenceNumber the new sequence number
	 * @throws GeneralSecurityException if the fragment could not be re-encrypted
	 */
	public synchronized void setSequenceNumber(long sequenceNumber) throws GeneralSecurityException {
		if (sequenceNumber > MAX_SEQUENCE_NO) {
			throw new IllegalArgumentException("Sequence number must have max 48 bits");
		}
		this.sequenceNumber = sequenceNumber;
		if (session != null && session.getWriteState() != null) {
			if (CipherType.BLOCK.equals(session.getWriteState().getCipherSuite().getCipherType())) {
				fragmentBytes = encryptBlockCipher(fragment.toByteArray());
			}
		}
	}

	public int getLength() {
		return length;
	}

//	public void setLength(int length) {
//		this.length = length;
//	}

//	public DTLSSession getSession() {
//		return session;
//	}

	public synchronized void setSession(DTLSSession session) {
		this.session = session;
	}
	
	/**
	 * Gets the <em>TLSPlaintext.fragment</em> from the record.
	 *  
	 * If necessary, the fragment is decrypted first according to the DTLS connection's
	 * <em>current</em> read state.
	 * 
	 * @return the plaintext fragment
	 * @throws InvalidMacException if message authentication failed
	 * @throws GeneralSecurityException if de-cryption fails, e.g. because
	 *             the JVM does not support the negotiated cipher algorithm
	 * @throws HandshakeException if the TLSPlaintext.fragment could not be parsed into
	 *             a valid handshake message
	 */
	public synchronized DTLSMessage getFragment() throws GeneralSecurityException, HandshakeException {
		if (fragment == null) {
			// decide, which type of fragment need de-cryption
			switch (type) {
			case ALERT:
				// http://tools.ietf.org/html/rfc5246#section-7.2:
				// "Like other messages, alert messages are encrypted and
				// compressed, as specified by the current connection state."
				byte[] decryptedMessage = decryptFragment(fragmentBytes);
				if (decryptedMessage != null) {
					fragment = AlertMessage.fromByteArray(decryptedMessage);
				}
				break;

			case APPLICATION_DATA:
				// http://tools.ietf.org/html/rfc5246#section-10:
				// "Application data messages are carried by the record layer and are
				//  fragmented, compressed, and encrypted based on the current connection
				//  state."
				decryptedMessage = decryptFragment(fragmentBytes);
				if (decryptedMessage != null) {
					fragment = ApplicationMessage.fromByteArray(decryptedMessage);
				}
				break;

			case CHANGE_CIPHER_SPEC:
				// http://tools.ietf.org/html/rfc5246#section-7.1:
				// "is encrypted and compressed under the current (not the pending)
				// connection state"
				decryptedMessage = decryptFragment(fragmentBytes);
				if (decryptedMessage != null) {
					fragment =  ChangeCipherSpecMessage.fromByteArray(decryptedMessage);
				}
				break;

			case HANDSHAKE:
				// TODO: it is unclear to me whether handshake messages are encrypted or not
				// http://tools.ietf.org/html/rfc5246#section-7.4:
				// "Handshake messages are supplied to the TLS record layer, where they
				//  are encapsulated within one or more TLSPlaintext structures, which
				//  are processed and transmitted as specified by the current active session state."
				if (LOGGER.isLoggable(Level.FINEST)) {
					LOGGER.log(Level.FINEST, "Decrypting HANDSHAKE message ciphertext\n{0}",
						ByteArrayUtils.toHexString(fragmentBytes));
				}
				decryptedMessage = decryptFragment(fragmentBytes);

				KeyExchangeAlgorithm keyExchangeAlgorithm = KeyExchangeAlgorithm.NULL;
				boolean receiveRawPublicKey = false;
				if (session != null) {
					keyExchangeAlgorithm = session.getKeyExchange();
					receiveRawPublicKey = session.receiveRawPublicKey();
					LOGGER.log(Level.FINEST, "Using KeyExchange [{0}] and receiveRawPublicKey [{1}] from session",
							new Object[]{keyExchangeAlgorithm, receiveRawPublicKey});
				}
				if (decryptedMessage != null) {
					if (LOGGER.isLoggable(Level.FINEST)) {
						LOGGER.log(Level.FINEST,
							"Parsing HANDSHAKE message plaintext using KeyExchange [{0}] and receiveRawPublicKey [{1}]\n{2}",
							new Object[]{keyExchangeAlgorithm, receiveRawPublicKey, ByteArrayUtils.toHexString(decryptedMessage)});
					}
					fragment = HandshakeMessage.fromByteArray(decryptedMessage, keyExchangeAlgorithm, receiveRawPublicKey);
				}
				break;
			}
		}
		
		return fragment;
	}

	/**
	 * Sets the DTLS fragment. At the same time, it creates the corresponding
	 * raw binary representation and encrypts it if necessary (depending on
	 * current connection state).
	 * 
	 * @param fragment the DTLS fragment
	 * @throws GeneralSecurityException if the message could not be encrypted, e.g.
	 *            because the JVM does not support the negotiated cipher suite's cipher algorithm
	 */
	public synchronized void setFragment(DTLSMessage fragment) throws GeneralSecurityException {

		if (fragmentBytes == null) {
			// serialize fragment and if necessary encrypt byte array

			byte[] byteArray = fragment.toByteArray();
			// the current length of the unprotected message
			// this value is needed to generate the additional data when using AEAD
			length = byteArray.length;

			switch (type) {
			case ALERT:
			case APPLICATION_DATA:
			case HANDSHAKE:
			case CHANGE_CIPHER_SPEC:
				byteArray = encryptFragment(byteArray);
				break;

			default:
				LOGGER.severe("Unknown content type: " + type.toString());
				break;
			}
			this.fragmentBytes = byteArray;

		}
		this.fragment = fragment;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("==[ DTLS Record ]==============================================");
		sb.append("\nContent Type: ").append(type.toString());
		sb.append("\nVersion: ").append(version.getMajor()).append(", ").append(version.getMinor());
		sb.append("\nEpoch: ").append(epoch);
		sb.append("\nSequence Number: ").append(sequenceNumber);
		sb.append("\nLength: ").append(length);
		sb.append("\nFragment:");
		if (fragment != null) {
			sb.append("\n").append(fragment);
		} else {
			sb.append("\nfragment is not decrypted yet\n");
		}
		sb.append("\n===============================================================");

		return sb.toString();
	}

}
