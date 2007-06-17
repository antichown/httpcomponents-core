/*
 * $HeadURL$
 * $Revision$
 * $Date$
 *
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.http.impl;

import java.io.IOException;
import java.util.Iterator;

import org.apache.http.Header;
import org.apache.http.HttpClientConnection;
import org.apache.http.HttpConnectionMetrics;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseFactory;
import org.apache.http.NoHttpResponseException;
import org.apache.http.ProtocolException;
import org.apache.http.StatusLine;
import org.apache.http.impl.entity.EntityDeserializer;
import org.apache.http.impl.entity.EntitySerializer;
import org.apache.http.impl.entity.LaxContentLengthStrategy;
import org.apache.http.impl.entity.StrictContentLengthStrategy;
import org.apache.http.io.HttpDataReceiver;
import org.apache.http.io.HttpDataTransmitter;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicRequestLine;
import org.apache.http.message.BasicStatusLine;
import org.apache.http.message.BufferedHeader;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.CharArrayBuffer;
import org.apache.http.util.HeaderUtils;

/**
 * Abstract client-side HTTP connection capable of transmitting and receiving data
 * using arbitrary {@link HttpDataReceiver} and {@link HttpDataTransmitter}
 *
 * @author <a href="mailto:oleg at ural.ru">Oleg Kalnichevski</a>
 *
 * @version $Revision$
 * 
 * @since 4.0
 */
public abstract class AbstractHttpClientConnection implements HttpClientConnection {

    private final CharArrayBuffer buffer; 
    private final EntitySerializer entityserializer;
    private final EntityDeserializer entitydeserializer;
    private final HttpResponseFactory responsefactory;
    
    private HttpDataReceiver datareceiver = null;
    private HttpDataTransmitter datatransmitter = null;

    private int maxHeaderCount = -1;
    private int maxLineLen = -1;
    private int maxGarbageLines = -1;

    private HttpConnectionMetricsImpl metrics;
    
    public AbstractHttpClientConnection() {
        super();
        this.buffer = new CharArrayBuffer(128);
        this.entityserializer = createEntitySerializer();
        this.entitydeserializer = createEntityDeserializer();
        this.responsefactory = createHttpResponseFactory();
    }
    
    protected abstract void assertOpen() throws IllegalStateException;

    protected EntityDeserializer createEntityDeserializer() {
        return new EntityDeserializer(new LaxContentLengthStrategy());
    }

    protected EntitySerializer createEntitySerializer() {
        return new EntitySerializer(new StrictContentLengthStrategy());
    }

    protected HttpResponseFactory createHttpResponseFactory() {
        return new DefaultHttpResponseFactory();
    }

    protected void init(
            final HttpDataReceiver datareceiver,
            final HttpDataTransmitter datatransmitter,
            final HttpParams params) {
        if (datareceiver == null) {
            throw new IllegalArgumentException("HTTP data receiver may not be null");
        }
        if (datatransmitter == null) {
            throw new IllegalArgumentException("HTTP data transmitter may not be null");
        }
        this.datareceiver = datareceiver;
        this.datatransmitter = datatransmitter;
        this.maxHeaderCount = params.getIntParameter(
                HttpConnectionParams.MAX_HEADER_COUNT, -1);
        this.maxLineLen = params.getIntParameter(
                HttpConnectionParams.MAX_LINE_LENGTH, -1);
        this.maxGarbageLines = params.getIntParameter(
                HttpConnectionParams.MAX_STATUS_LINE_GARBAGE, Integer.MAX_VALUE);
        this.metrics = new HttpConnectionMetricsImpl(
                datareceiver.getMetrics(),
                datatransmitter.getMetrics());
    }
    
    public boolean isResponseAvailable(int timeout) throws IOException {
        assertOpen();
        return this.datareceiver.isDataAvailable(timeout);
    }

    public void sendRequestHeader(final HttpRequest request) 
            throws HttpException, IOException {
        if (request == null) {
            throw new IllegalArgumentException("HTTP request may not be null");
        }
        assertOpen();
        sendRequestLine(request);
        sendRequestHeaders(request);
        this.metrics.incrementRequestCount();
    }

    public void sendRequestEntity(final HttpEntityEnclosingRequest request) 
            throws HttpException, IOException {
        if (request == null) {
            throw new IllegalArgumentException("HTTP request may not be null");
        }
        assertOpen();
        if (request.getEntity() == null) {
            return;
        }
        this.entityserializer.serialize(
                this.datatransmitter,
                request,
                request.getEntity());
    }

    protected void doFlush() throws IOException {
        this.datatransmitter.flush();
    }
    
    public void flush() throws IOException {
        assertOpen();
        doFlush();
    }
    
    protected void sendRequestLine(final HttpRequest request) 
            throws HttpException, IOException {
        this.buffer.clear();
        BasicRequestLine.format(this.buffer, request.getRequestLine());
        this.datatransmitter.writeLine(this.buffer);
    }

    protected void sendRequestHeaders(final HttpRequest request) 
            throws HttpException, IOException {
        for (Iterator it = request.headerIterator(); it.hasNext(); ) {
            Header header = (Header) it.next();
            if (header instanceof BufferedHeader) {
                // If the header is backed by a buffer, re-use the buffer
                this.datatransmitter.writeLine(((BufferedHeader)header).getBuffer());
            } else {
                this.buffer.clear();
                BasicHeader.format(this.buffer, header);
                this.datatransmitter.writeLine(this.buffer);
            }
        }
        this.buffer.clear();
        this.datatransmitter.writeLine(this.buffer);
    }

    public HttpResponse receiveResponseHeader() 
            throws HttpException, IOException {
        assertOpen();
        HttpResponse response = readResponseStatusLine();
        readResponseHeaders(response);
        if (response.getStatusLine().getStatusCode() >= 200) {
            this.metrics.incrementResponseCount();
        }
        return response;
    }
    
    public void receiveResponseEntity(final HttpResponse response)
            throws HttpException, IOException {
        if (response == null) {
            throw new IllegalArgumentException("HTTP response may not be null");
        }
        assertOpen();
        HttpEntity entity = this.entitydeserializer.deserialize(this.datareceiver, response);
        response.setEntity(entity);
    }
    
    /**
     * Tests if the string starts with 'HTTP' signature.
     * @param buffer buffer to test
     * @return <tt>true</tt> if the line starts with 'HTTP' 
     *   signature, <tt>false</tt> otherwise.
     */
    protected static boolean startsWithHTTP(final CharArrayBuffer buffer) {
        try {
            int i = 0;
            while (HTTP.isWhitespace(buffer.charAt(i))) {
                ++i;
            }
            return buffer.charAt(i) == 'H' 
                && buffer.charAt(i + 1) == 'T'
                && buffer.charAt(i + 2) == 'T'
                && buffer.charAt(i + 3) == 'P';
        } catch (IndexOutOfBoundsException e) {
            return false;
        }
    }
    
    protected HttpResponse readResponseStatusLine() 
                throws HttpException, IOException {
        // clear the buffer
        this.buffer.clear();
        //read out the HTTP status string
        int count = 0;
        do {
            int i = this.datareceiver.readLine(this.buffer);
            if (i == -1 && count == 0) {
                // The server just dropped connection on us
                throw new NoHttpResponseException("The target server failed to respond");
            }
            if (startsWithHTTP(this.buffer)) {
                // Got one
                break;
            } else if (i == -1 || count >= this.maxGarbageLines) {
                // Giving up
                throw new ProtocolException("The server failed to respond with a " +
                        "valid HTTP response");
            }
            count++;
        } while(true);
        //create the status line from the status string
        StatusLine statusline = BasicStatusLine.parse(this.buffer, 0, this.buffer.length());
        return this.responsefactory.newHttpResponse(statusline, null);
    }

    protected void readResponseHeaders(final HttpResponse response) 
            throws HttpException, IOException {
        Header[] headers = HeaderUtils.parseHeaders(
                this.datareceiver, 
                this.maxHeaderCount,
                this.maxLineLen);
        response.setHeaders(headers);
    }

    public boolean isStale() {
        assertOpen();
        try {
            this.datareceiver.isDataAvailable(1);
            return false;
        } catch (IOException ex) {
            return true;
        }
    }
    
    public HttpConnectionMetrics getMetrics() {
        return this.metrics;
    }

}
