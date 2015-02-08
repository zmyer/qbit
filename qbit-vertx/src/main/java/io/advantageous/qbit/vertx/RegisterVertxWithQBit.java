package io.advantageous.qbit.vertx;

import io.advantageous.qbit.spi.FactorySPI;
import io.advantageous.qbit.vertx.http.HttpVertxClient;
import io.advantageous.qbit.vertx.http.HttpServerVertxFactory;

/**
 * Created by rhightower on 11/6/14.
 */
public class RegisterVertxWithQBit {

    public static void registerVertxWithQBit() {
        FactorySPI.setHttpServerFactory(new HttpServerVertxFactory());

        FactorySPI.setHttpClientFactory((host, port, requestBatchSize, timeOutInMilliseconds, poolSize, autoFlush, flushRate, keepAlive, pipeline)
                -> new HttpVertxClient(host, port, requestBatchSize,
                timeOutInMilliseconds, poolSize, autoFlush, flushRate, keepAlive, pipeline));

    }
}
