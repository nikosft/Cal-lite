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



/**
 * The base exception class for all exceptions during a DTLS handshake.
 */
public class HandshakeException extends Exception {

	private static final long serialVersionUID = 1123415935894222594L;

	private AlertMessage alert;
	private String message;

	public HandshakeException(String message, AlertMessage alert) {
		this.alert = alert;
		this.message = message;
	}

	public AlertMessage getAlert() {
		return alert;
	}

	public String getMessage() {
		return message;
	}

}
