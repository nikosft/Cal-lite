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
package org.eclipse.californium.scandium.dtls;

import org.eclipse.californium.scandium.util.ByteArrayUtils;
import org.eclipse.californium.scandium.util.DatagramReader;
import org.eclipse.californium.scandium.util.DatagramWriter;


/**
 * The server send this request after receiving a {@link ClientHello} message to
 * prevent Denial-of-Service Attacks. See <a
 * href="http://tools.ietf.org/html/rfc6347#section-4.2.1">RFC 6347</a> for the
 * definition.
 */
public class HelloVerifyRequest extends HandshakeMessage {

	// DTLS-specific constants ///////////////////////////////////////////

	private static final int VERSION_BITS = 8; // for major and minor each

	private static final int COOKIE_LENGTH_BITS = 8;

	// Members ///////////////////////////////////////////////////////////

	/**
	 * This field will contain the lower of that suggested by the client in the
	 * client hello and the highest supported by the server.
	 */
	private ProtocolVersion serverVersion;

	/** The cookie which needs to be replayed by the client. */
	private Cookie cookie;
	
	// Constructor ////////////////////////////////////////////////////

	public HelloVerifyRequest(ProtocolVersion version, Cookie cookie) {
		this.serverVersion = version;
		this.cookie = cookie;
	}
	
	// Serialization //////////////////////////////////////////////////

	@Override
	public byte[] fragmentToByteArray() {
		DatagramWriter writer = new DatagramWriter();

		writer.write(serverVersion.getMajor(), VERSION_BITS);
		writer.write(serverVersion.getMinor(), VERSION_BITS);

		writer.write(cookie.length(), COOKIE_LENGTH_BITS);
		writer.writeBytes(cookie.getCookie());

		return writer.toByteArray();
	}

	public static HandshakeMessage fromByteArray(byte[] byteArray) {
		DatagramReader reader = new DatagramReader(byteArray);

		int major = reader.read(VERSION_BITS);
		int minor = reader.read(VERSION_BITS);
		ProtocolVersion version = new ProtocolVersion(major, minor);

		int cookieLength = reader.read(COOKIE_LENGTH_BITS);
		Cookie cookie = new Cookie(reader.readBytes(cookieLength));

		return new HelloVerifyRequest(version, cookie);
	}
	
	// Methods ////////////////////////////////////////////////////////

	@Override
	public HandshakeType getMessageType() {
		return HandshakeType.HELLO_VERIFY_REQUEST;
	}

	@Override
	public int getMessageLength() {
		// fixed: version (2) + cookie length (1)
		return 3 + cookie.length();
	}

	public ProtocolVersion getServerVersion() {
		return serverVersion;
	}

	public Cookie getCookie() {
		return cookie;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(super.toString());
		sb.append("\t\tServer Version: " + serverVersion.getMajor() + ", " + serverVersion.getMinor() + "\n");
		sb.append("\t\tCookie Length: " + cookie.length() + "\n");
		sb.append("\t\tCookie: " + ByteArrayUtils.toHexString(cookie.getCookie()) + "\n");

		return sb.toString();
	}

}
