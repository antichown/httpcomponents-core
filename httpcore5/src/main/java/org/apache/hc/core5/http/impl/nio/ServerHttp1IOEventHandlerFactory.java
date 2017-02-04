/*
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

package org.apache.hc.core5.http.impl.nio;

import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.http.ConnectionReuseStrategy;
import org.apache.hc.core5.http.ContentLengthStrategy;
import org.apache.hc.core5.http.ExceptionListener;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.config.CharCodingConfig;
import org.apache.hc.core5.http.config.H1Config;
import org.apache.hc.core5.http.impl.ConnectionListener;
import org.apache.hc.core5.http.impl.DefaultConnectionReuseStrategy;
import org.apache.hc.core5.http.impl.DefaultContentLengthStrategy;
import org.apache.hc.core5.http.impl.Http1StreamListener;
import org.apache.hc.core5.http.nio.AsyncServerExchangeHandler;
import org.apache.hc.core5.http.nio.HandlerFactory;
import org.apache.hc.core5.http.nio.NHttpMessageParserFactory;
import org.apache.hc.core5.http.nio.NHttpMessageWriterFactory;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.http.protocol.HttpProcessor;
import org.apache.hc.core5.reactor.IOEventHandler;
import org.apache.hc.core5.reactor.IOEventHandlerFactory;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.ssl.TransportSecurityLayer;
import org.apache.hc.core5.util.Args;

/**
 * @since 5.0
 */
@Contract(threading = ThreadingBehavior.IMMUTABLE)
public class ServerHttp1IOEventHandlerFactory implements IOEventHandlerFactory {

    private final HttpProcessor httpProcessor;
    private final HandlerFactory<AsyncServerExchangeHandler> exchangeHandlerFactory;
    private final ConnectionReuseStrategy connectionReuseStrategy;
    private final CharCodingConfig charCodingConfig;
    private final NHttpMessageParserFactory<HttpRequest> requestParserFactory;
    private final NHttpMessageWriterFactory<HttpResponse> responseWriterFactory;
    private final ContentLengthStrategy incomingContentStrategy;
    private final ContentLengthStrategy outgoingContentStrategy;
    private final TlsStrategy tlsStrategy;
    private final ConnectionListener connectionListener;
    private final Http1StreamListener streamListener;

    public ServerHttp1IOEventHandlerFactory(
            final HttpProcessor httpProcessor,
            final HandlerFactory<AsyncServerExchangeHandler> exchangeHandlerFactory,
            final CharCodingConfig charCodingConfig,
            final ConnectionReuseStrategy connectionReuseStrategy,
            final NHttpMessageParserFactory<HttpRequest> requestParserFactory,
            final NHttpMessageWriterFactory<HttpResponse> responseWriterFactory,
            final ContentLengthStrategy incomingContentStrategy,
            final ContentLengthStrategy outgoingContentStrategy,
            final TlsStrategy tlsStrategy,
            final ConnectionListener connectionListener,
            final Http1StreamListener streamListener) {
        this.httpProcessor = Args.notNull(httpProcessor, "HTTP processor");
        this.exchangeHandlerFactory = Args.notNull(exchangeHandlerFactory, "Exchange handler factory");
        this.charCodingConfig = charCodingConfig != null ? charCodingConfig : CharCodingConfig.DEFAULT;
        this.connectionReuseStrategy = connectionReuseStrategy != null ? connectionReuseStrategy :
                DefaultConnectionReuseStrategy.INSTANCE;
        this.requestParserFactory = requestParserFactory != null ? requestParserFactory :
                DefaultHttpRequestParserFactory.INSTANCE;
        this.responseWriterFactory = responseWriterFactory != null ? responseWriterFactory :
                DefaultHttpResponseWriterFactory.INSTANCE;
        this.incomingContentStrategy = incomingContentStrategy != null ? incomingContentStrategy :
                DefaultContentLengthStrategy.INSTANCE;
        this.outgoingContentStrategy = outgoingContentStrategy != null ? outgoingContentStrategy :
                DefaultContentLengthStrategy.INSTANCE;
        this.tlsStrategy = tlsStrategy;
        this.connectionListener = connectionListener;
        this.streamListener = streamListener;
    }

    public ServerHttp1IOEventHandlerFactory(
            final HttpProcessor httpProcessor,
            final HandlerFactory<AsyncServerExchangeHandler> exchangeHandlerFactory,
            final CharCodingConfig charCodingConfig,
            final ConnectionReuseStrategy connectionReuseStrategy,
            final NHttpMessageParserFactory<HttpRequest> requestParserFactory,
            final NHttpMessageWriterFactory<HttpResponse> responseWriterFactory,
            final TlsStrategy tlsStrategy,
            final ConnectionListener connectionListener,
            final Http1StreamListener streamListener) {
        this(httpProcessor, exchangeHandlerFactory, charCodingConfig,
                connectionReuseStrategy, requestParserFactory, responseWriterFactory,
                null, null, tlsStrategy, connectionListener, streamListener);
    }

    public ServerHttp1IOEventHandlerFactory(
            final HttpProcessor httpProcessor,
            final HandlerFactory<AsyncServerExchangeHandler> exchangeHandlerFactory,
            final CharCodingConfig charCodingConfig,
            final TlsStrategy tlsStrategy,
            final ConnectionListener connectionListener,
            final Http1StreamListener streamListener) {
        this(httpProcessor, exchangeHandlerFactory, charCodingConfig, null, null ,null,
                tlsStrategy, connectionListener, streamListener);
    }

    public ServerHttp1IOEventHandlerFactory(
            final HttpProcessor httpProcessor,
            final HandlerFactory<AsyncServerExchangeHandler> exchangeHandlerFactory,
            final CharCodingConfig charCodingConfig,
            final TlsStrategy tlsStrategy,
            final ExceptionListener errorListener) {
        this(httpProcessor, exchangeHandlerFactory, charCodingConfig, tlsStrategy, null, null);
    }

    @Override
    public IOEventHandler createHandler(final IOSession ioSession, final Object attachment) {
        return new ServerHttp1IOEventHandler(createStreamDuplexer(ioSession));
    }

    protected ServerHttp1StreamDuplexer createStreamDuplexer(final IOSession ioSession) {
        if (tlsStrategy != null && ioSession instanceof TransportSecurityLayer) {
            tlsStrategy.upgrade(
                    (TransportSecurityLayer) ioSession,
                    null,
                    ioSession.getLocalAddress(),
                    ioSession.getRemoteAddress());
        }
        return new ServerHttp1StreamDuplexer(ioSession, httpProcessor, exchangeHandlerFactory,
                H1Config.DEFAULT,
                charCodingConfig,
                connectionReuseStrategy,
                requestParserFactory.create(),
                responseWriterFactory.create(),
                incomingContentStrategy,
                outgoingContentStrategy,
                connectionListener,
                streamListener);
    }

}
