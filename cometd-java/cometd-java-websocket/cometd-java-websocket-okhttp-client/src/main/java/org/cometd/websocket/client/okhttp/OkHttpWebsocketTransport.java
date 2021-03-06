/*
 * Copyright (c) 2008-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cometd.websocket.client.okhttp;

import java.io.IOException;
import java.net.ConnectException;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.channels.UnresolvedAddressException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.cometd.bayeux.Message;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.TransportListener;
import org.cometd.websocket.client.common.AbstractWebSocketTransport;
import org.eclipse.jetty.util.component.ContainerLifeCycle;

public class OkHttpWebsocketTransport extends AbstractWebSocketTransport {
    // We specifically do not want to have a long blocking handshake, to allow for more request retries
    private static final String SEC_WEB_SOCKET_PROTOCOL_HEADER = "Sec-WebSocket-Protocol";
    private static final String SEC_WEB_SOCKET_ACCEPT_HEADER = "Sec-WebSocket-Accept";

    private final OkHttpClient okHttpClient;
    private boolean webSocketSupported;
    private boolean webSocketConnected;

    public OkHttpWebsocketTransport(Map<String, Object> options, OkHttpClient okHttpClient) {
        this(null, options, null, okHttpClient);
    }

    public OkHttpWebsocketTransport(String uri, Map<String, Object> options, ScheduledExecutorService scheduler, OkHttpClient okHttpClient) {
        super(uri, options, scheduler);
        OkHttpClient.Builder enrichedClient = okHttpClient.newBuilder()
                .connectTimeout(getConnectTimeout(), TimeUnit.MILLISECONDS);
        if (okHttpClient.pingIntervalMillis() == 0) {
            enrichedClient.pingInterval(20, TimeUnit.SECONDS);
        }
        this.okHttpClient = enrichedClient.build();
        this.webSocketSupported = true;
    }

    @Override
    public void init() {
        super.init();
        this.webSocketSupported = true;
        this.webSocketConnected = false;
    }

    @Override
    public boolean accept(String s) {
        return webSocketSupported;
    }

    @Override
    protected Delegate connect(String uri, TransportListener listener, List<Message.Mutable> messages) {
        try {
            // We must make the okhttp call blocking for CometD to handshake properly.
            CompletableFuture<Throwable> connectCompletable = new CompletableFuture<>();
            OkHttpDelegate delegate = new OkHttpDelegate(connectCompletable);
            Request upgradeRequest = buildUpgradeRequest(uri);
            okHttpClient.newWebSocket(upgradeRequest, delegate.listener);
            Throwable connectFailure = connectCompletable.get(getConnectTimeout(), TimeUnit.MILLISECONDS);
            if (connectFailure != null) {
                throw connectFailure;
            }
            this.webSocketConnected = true;
            return delegate;
        } catch (ConnectException | SocketTimeoutException | TimeoutException | UnresolvedAddressException e) {
            listener.onFailure(e, messages);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.onFailure(e, messages);
        } catch (Throwable e) {
            webSocketSupported = isStickyReconnect() && webSocketConnected;
            listener.onFailure(e, messages);
        }
        return null;
    }

    private Request buildUpgradeRequest(String uri) {
        String protocol = getProtocol();
        Request.Builder upgradeRequest = new Request.Builder().url(uri);
        if (protocol != null && !protocol.isEmpty()) {
            upgradeRequest.header(SEC_WEB_SOCKET_PROTOCOL_HEADER, protocol);
        }
        CookieStore cookieStore = getCookieStore();
        List<HttpCookie> cookies = cookieStore.get(URI.create(uri));
        for (HttpCookie cookie : cookies) {
            String cookieValue = cookie.getName() + "=" + cookie.getValue();
            upgradeRequest.addHeader(COOKIE_HEADER, cookieValue);
        }
        return upgradeRequest.build();
    }

    private class OkHttpDelegate extends Delegate {
        private final WebSocketListener listener = new OkHttpListener();
        private final CompletableFuture<Throwable> connectCompletable;
        private WebSocket webSocket;

        private OkHttpDelegate(CompletableFuture<Throwable> connectCompletable) {
            this.connectCompletable = connectCompletable;
        }

        private void onOpen(WebSocket webSocket, Response response) {
            synchronized (this) {
                this.webSocket = webSocket;
            }
            webSocketSupported = response.header(SEC_WEB_SOCKET_ACCEPT_HEADER) != null;
            storeCookies(response.headers().toMultimap());
            if (logger.isDebugEnabled()) {
                logger.debug("Opened {}", webSocket);
            }
        }

        @Override
        protected void send(String payload) {
            final WebSocket webSocket;
            synchronized (this) {
                webSocket = this.webSocket;
            }
            try {
                if (webSocket == null) {
                    throw new IOException("Unconnected!");
                }
                boolean enqueued = webSocket.send(payload);
                if (!enqueued) {
                    throw new IOException("Not enqueued! Current queue size: " + webSocket.queueSize());
                }
            } catch (Throwable throwable) {
                logger.warn("Failure sending " + payload, throwable);
                fail(throwable, "Exception");
            }
        }

        @Override
        protected boolean isOpen() {
            synchronized (this) {
                return webSocket != null;
            }
        }

        @Override
        protected void close() {
            synchronized (this) {
                webSocket = null;
            }
        }

        @Override
        protected void shutdown(String reason) {
            final WebSocket webSocket;
            synchronized (this) {
                webSocket = this.webSocket;
                close();
            }

            if (webSocket != null) {
                int code = NORMAL_CLOSE_CODE;
                if (logger.isDebugEnabled()) {
                    logger.debug("Closing websocket {}/{}", code, reason);
                }
                try {
                    reason = trimCloseReason(reason);
                    webSocket.close(code, reason);
                } catch (Throwable t) {
                    logger.warn(String.format("Unable to close websocket %d/%s", code, reason), t);
                }
            }
        }

        private final class OkHttpListener extends WebSocketListener {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                OkHttpDelegate.this.onOpen(webSocket, response);
                connectCompletable.complete(null);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                OkHttpDelegate.this.onData(text);
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                OkHttpDelegate.this.onClose(code, reason);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable failure, Response response) {
                if (!connectCompletable.complete(failure))
                    OkHttpDelegate.this.failMessages(failure);
            }
        }
    }

    public static class Factory extends ContainerLifeCycle implements ClientTransport.Factory {
        private final OkHttpClient okHttpClient;

        public Factory(OkHttpClient okHttpClient) {
            this.okHttpClient = okHttpClient;
            addBean(okHttpClient);
        }

        @Override
        public ClientTransport newClientTransport(String url, Map<String, Object> options) {
            ScheduledExecutorService scheduler = (ScheduledExecutorService)options.get(ClientTransport.SCHEDULER_OPTION);
            return new OkHttpWebsocketTransport(url, options, scheduler, okHttpClient);
        }
    }
}
