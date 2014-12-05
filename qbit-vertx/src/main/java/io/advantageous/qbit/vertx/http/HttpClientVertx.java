package io.advantageous.qbit.vertx.http;

import io.advantageous.qbit.http.HttpClient;
import io.advantageous.qbit.http.HttpRequest;
import io.advantageous.qbit.http.WebSocketMessage;
import io.advantageous.qbit.queue.ReceiveQueueListener;
import io.advantageous.qbit.queue.SendQueue;
import io.advantageous.qbit.queue.impl.BasicQueue;
import io.advantageous.qbit.util.MultiMap;
import io.advantageous.qbit.vertx.MultiMapWrapper;
import org.boon.Str;
import org.boon.core.Sys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.VertxFactory;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.http.HttpClientRequest;
import org.vertx.java.core.http.HttpClientResponse;
import org.vertx.java.core.http.HttpHeaders;
import org.vertx.java.core.http.WebSocket;

import java.net.ConnectException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static org.boon.Boon.puts;

/**
 * Created by rhightower on 10/28/14.
 *
 * @author rhightower
 */
public class HttpClientVertx implements HttpClient {


    private final Logger logger = LoggerFactory.getLogger(HttpClientVertx.class);

    private final boolean debug = logger.isDebugEnabled();


    /**
     * I am leaving these protected and non-final so subclasses can use injection frameworks for them.
     */
    protected  int port;
    protected  int pollTime=10;
    protected int requestBatchSize=50;
    protected  String host;
    protected  int timeOutInMilliseconds;
    protected  int poolSize;
    protected org.vertx.java.core.http.HttpClient httpClient;
    protected Vertx vertx;
    protected boolean autoFlush;
    protected boolean keepAlive = true;
    protected boolean pipeline = false;
    protected  int flushInterval = 10;
    protected ReentrantLock requestLock = new ReentrantLock();


    private final Map<String, WebSocket> webSocketMap = new ConcurrentHashMap<>();


    protected ScheduledExecutorService scheduledExecutorService;


    private BasicQueue<HttpRequest> requestQueue;
    private BasicQueue<WebSocketMessage> webSocketMessageQueue;

    private  SendQueue<HttpRequest> httpRequestSendQueue;
    private  SendQueue<WebSocketMessage> webSocketSendQueue;

    /**
     * Are we closed.
     */
    private final AtomicBoolean closed = new AtomicBoolean();
    private Consumer<Void> periodicFlushCallback = new Consumer<Void>() {
        @Override
        public void accept(Void aVoid) {

        }
    };


    public HttpClientVertx(String host, int port, int pollTime, int requestBatchSize, int timeOutInMilliseconds, int poolSize, boolean autoFlush) {

        this.port = port;
        this.host = host;
        this.timeOutInMilliseconds = timeOutInMilliseconds;
        this.poolSize = poolSize;
        this.vertx = VertxFactory.newVertx();
        this.autoFlush = autoFlush;
        this.pollTime = pollTime;
        this.requestBatchSize = requestBatchSize;
        this.poolSize = poolSize;

    }

    @Override
    public void sendHttpRequest(final HttpRequest request) {



        if(debug) logger.debug("HTTP CLIENT: sendHttpRequest:: \n{}\n", request);


        requestLock.lock();

        try {
            httpRequestSendQueue.send(request);

        } finally {

            requestLock.unlock();
        }
    }

    @Override
    public void sendWebSocketMessage(final WebSocketMessage webSocketMessage) {

        requestLock.lock();

        try {

            if (debug) logger.debug("HTTP CLIENT: sendWebSocketMessage:: \n{}\n", webSocketMessage);
            webSocketSendQueue.send(webSocketMessage);

        } finally {
            requestLock.unlock();
        }

    }

    @Override
    public void periodicFlushCallback(Consumer<Void> periodicFlushCallback) {
        this.periodicFlushCallback = periodicFlushCallback;
    }


    private void autoFlush() {
        requestLock.lock();

        try {
            httpRequestSendQueue.flushSends();
            webSocketSendQueue.flushSends();
        } finally {
            requestLock.unlock();
        }

    }


    @Override
    public void stop() {
        try {
            if (this.scheduledExecutorService!=null)
                this.scheduledExecutorService.shutdown();
        } catch (Exception ex) {
            logger.warn("problem shutting down executor service for Http Client", ex);
        }

        try {
            if (requestQueue!=null) {
                requestQueue.stop();
            }
        } catch (Exception ex) {

            logger.warn("problem shutting down requestQueue for Http Client", ex);
        }

        try {
            if (webSocketMessageQueue!=null) {

                webSocketMessageQueue.stop();
            }

        } catch (Exception ex) {

            logger.warn("problem shutting down webSocketMessageQueue for Http Client", ex);
        }

        try {
            if (httpClient != null) {
                httpClient.close();
            }
        }catch (Exception ex) {

            logger.warn("problem shutting down vertx httpClient for QBIT Http Client", ex);
        }

    }

    @Override
    public void run() {
        requestQueue = new BasicQueue<>("HTTP REQUEST queue " + host + ":" + port, 50, TimeUnit.MILLISECONDS, requestBatchSize);

        webSocketMessageQueue = new BasicQueue<>("WebSocket queue " + host + ":" + port, 50, TimeUnit.MILLISECONDS, requestBatchSize);

        httpRequestSendQueue = requestQueue.sendQueue();
        webSocketSendQueue = webSocketMessageQueue.sendQueue();
        scheduledExecutorService = Executors.newScheduledThreadPool(3);

        connect();

        this.scheduledExecutorService.scheduleAtFixedRate(this::connectWithRetry, 0, 10, TimeUnit.SECONDS);

        if (autoFlush) {
            periodicFlushCallback.accept(null);

            this.scheduledExecutorService.scheduleAtFixedRate(this::autoFlush, 0, flushInterval, TimeUnit.MILLISECONDS);
        }


        Sys.sleep(100);


        webSocketMessageQueue.startListener(new ReceiveQueueListener<WebSocketMessage>() {
            @Override
            public void receive(WebSocketMessage item) {
                doSendWebSocketMessageToServer(item);
            }

            @Override
            public void empty() {

            }

            @Override
            public void limit() {

            }

            @Override
            public void shutdown() {

            }

            @Override
            public void idle() {
                autoFlush();
            }
        });


        requestQueue.startListener(new ReceiveQueueListener<HttpRequest>() {
            @Override
            public void receive(final HttpRequest request) {

                doSendRequestToServer(request, httpClient);
            }

            @Override
            public void empty() {



            }

            @Override
            public void limit() {

            }

            @Override
            public void shutdown() {

            }

            @Override
            public void idle() {
                autoFlush();
            }
        });

    }



    private void doSendWebSocketMessageToServer(final WebSocketMessage webSocketMessage) {

        final String uri = webSocketMessage.getUri();

        WebSocket webSocket = webSocketMap.get(uri);

        if (webSocket!=null) {
            try {
                webSocket.writeTextFrame(webSocketMessage.getMessage());
            } catch (Exception ex) {
                connectWebSocketAndSend(webSocketMessage);
            }
        } else {
            connectWebSocketAndSend(webSocketMessage);
        }
    }


    private void connectWebSocketAndSend(final WebSocketMessage webSocketMessage) {


        final String uri = webSocketMessage.getUri();

        WebSocket webSocket = webSocketMap.get(uri);

        if (webSocket == null) {

            final BlockingQueue<WebSocket> connectQueue = new ArrayBlockingQueue<WebSocket>(1);

            httpClient.connectWebsocket(uri, new Handler<WebSocket>(){
                @Override
                public void handle(final WebSocket webSocket) {

                    webSocketMap.put(uri, webSocket);

                    connectQueue.offer(webSocket);


                    webSocket.dataHandler(new Handler<Buffer>() {
                        @Override
                        public void handle(Buffer buffer) {

                            webSocketMessage.getSender().send(buffer.toString());
                        }
                    });

                    webSocket.closeHandler(new Handler<Void>() {
                        @Override
                        public void handle(Void event) {
                            logger.debug("Closed WebSocket " + uri);

                            webSocketMap.remove(uri);
                        }
                    });

                    webSocket.exceptionHandler(new Handler<Throwable>() {
                        @Override
                        public void handle(Throwable event) {
                            logger.warn("Problem with WebSocket connection " + uri, event);
                        }
                    });

                    webSocket.endHandler(new Handler<Void>() {
                        @Override
                        public void handle(Void event) {

                            logger.debug("End WebSocket Message" + uri);
                        }
                    });

                }
            });


            try {
                webSocket = connectQueue.poll(timeOutInMilliseconds, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                logger.warn("Unable to get connection interrupted thread", e);

            }

        }

        if (webSocket!=null) {
            webSocket.writeTextFrame(webSocketMessage.getMessage());

        }

    }

    private void doSendRequestToServer(HttpRequest request, final org.vertx.java.core.http.HttpClient remoteHttpServer) {
        if (debug) logger.debug("HttpClientVertx::doSendRequestToServer::\n request={}", request);


        final HttpClientRequest httpClientRequest = remoteHttpServer.request(
                request.getMethod(), request.getUri(),
                httpClientResponse -> handleResponse(request, httpClientResponse));

        final MultiMap<String, String> headers = request.getHeaders();

        if (headers!=null) {

            for (String key : headers.keySet()) {
                httpClientRequest.putHeader(key, headers.getAll(key));
            }
        }

        final byte[] body = request.getBody();

        if (keepAlive) {
            httpClientRequest.putHeader(HttpHeaders.CONNECTION, HttpHeaders.KEEP_ALIVE);
        }


        if (body != null && body.length > 0) {


            httpClientRequest.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(body.length));
            if (request.getContentType()!=null) {


                    httpClientRequest.putHeader("Content-Type", request.getContentType());
                }
                httpClientRequest.end(new Buffer(request.getBody()));

        } else {
            httpClientRequest.end();
        }

        if (debug) logger.debug("HttpClientVertx::SENT \n{}", request);
    }

    @Override
    public void flush() {
        if (autoFlush) {
            autoFlush();
        } else {
            this.httpRequestSendQueue.flushSends();
            this.webSocketSendQueue.flushSends();
        }
    }


    private void handleResponse(final HttpRequest request, final HttpClientResponse httpClientResponse) {
        final int statusCode = httpClientResponse.statusCode();
        final MultiMap<String, String> headers = httpClientResponse.headers().size() == 0 ? MultiMap.empty() : new MultiMapWrapper(httpClientResponse.headers());



        httpClientResponse.bodyHandler(buffer -> {
            final String body = buffer.toString("UTF-8");

            handleResponseFromServer(request, statusCode, headers, body);
        });
    }

    private void handleResponseFromServer(HttpRequest request, int responseStatusCode, MultiMap<String, String> responseHeaders, String body) {
        if(debug) {
            logger.debug("HttpClientVertx::handleResponseFromServer:: request = {}, response status code = {}, \n" +
                    "response headers = {}, body = {}", request, responseStatusCode, responseHeaders, body);
        }
        request.getResponse().response(responseStatusCode, responseHeaders.get("Content-Type"), body);
    }

    private void connectWithRetry() {
        int retry = 0;
        while (closed.get()) {

            /* Retry to connect every one second */
            Sys.sleep(1000);

            if (!closed.get()) {
                break;
            }
            retry++;
            if (retry > 10) {
                break;
            }

            if (retry % 3 == 0) {
                connect();
            }
        }
    }

    private void connect() {
        httpClient = vertx.createHttpClient().setHost(host).setPort(port)
                .setConnectTimeout(timeOutInMilliseconds).setMaxPoolSize(poolSize)
                .setKeepAlive(keepAlive).setPipelining(pipeline)
                .setSoLinger(100)
                .setTCPNoDelay(false)
                .setMaxWebSocketFrameSize(20_000_000)
                .setConnectTimeout(this.timeOutInMilliseconds);


        httpClient.setUsePooledBuffers(true);


        if(debug) logger.debug("HTTP CLIENT: connect:: \nhost {} \nport {}\n", host, port);

        httpClient.exceptionHandler(throwable -> {

            if (throwable instanceof ConnectException) {
                closed.set(true);
            } else {
                logger.error("Unable to connect to " + host + " port " + port, throwable);
            }
        });

        Sys.sleep(100);

    }
}
