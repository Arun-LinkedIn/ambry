/**
 * Copyright 2019 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.github.ambry.network;

import com.github.ambry.config.NetworkConfig;
import com.github.ambry.network.http2.Http2ServerMetrics;
import com.github.ambry.server.EmptyRequest;
import com.github.ambry.utils.SystemTime;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * RequestResponseChannel for Netty based server
 */
public class NettyServerRequestResponseChannel implements RequestResponseChannel {
  private static final Logger logger = LoggerFactory.getLogger(NettyServerRequestResponseChannel.class);
  private final Http2ServerMetrics http2ServerMetrics;
  private final NetworkRequestQueue networkRequestQueue;
  private final Queue<NetworkRequest> unqueuedRequests = new ConcurrentLinkedQueue<>();

  public NettyServerRequestResponseChannel(NetworkConfig config, Http2ServerMetrics http2ServerMetrics) {
    switch (config.requestQueueType) {
      case ADAPTIVE_LIFO_CO_DEL:
        this.networkRequestQueue =
            new AdaptiveLifoCoDelNetworkRequestQueue(config.queuedMaxRequests, config.adaptiveLifoQueueThreshold,
                config.adaptiveLifoQueueCodelTargetDelayMs, config.requestQueueTimeoutMs, SystemTime.getInstance());
        break;
      case BASIC_FIFO:
        this.networkRequestQueue = new FifoNetworkRequestQueue(config.queuedMaxRequests, config.requestQueueTimeoutMs,
            SystemTime.getInstance());
        break;
      default:
        throw new IllegalArgumentException("Queue type not supported by channel: " + config.requestQueueType);
    }
    this.http2ServerMetrics = http2ServerMetrics;
  }

  /** Send a request to be handled, potentially blocking until there is room in the queue for the request */
  @Override
  public void sendRequest(NetworkRequest request) throws InterruptedException {
    if (!networkRequestQueue.offer(request)) {
      logger.debug("Request queue is full, dropping incoming request: {}", request);
      unqueuedRequests.add(request);
    }
    http2ServerMetrics.requestEnqueueTime.update(System.currentTimeMillis() - request.getStartTimeInMs());
  }

  /** Send a response back via netty outbound handlers */
  @Override
  public void sendResponse(Send payloadToSend, NetworkRequest originalRequest, ServerNetworkResponseMetrics metrics)
      throws InterruptedException {

    if (!(originalRequest instanceof NettyServerRequest)) {
      throw new IllegalArgumentException("NetworkRequest should be NettyRequest");
    }
    ChannelHandlerContext ctx = ((NettyServerRequest) originalRequest).getCtx();
    http2ServerMetrics.requestTotalProcessingTime.update(
        System.currentTimeMillis() - originalRequest.getStartTimeInMs());
    long sendStartTime = System.currentTimeMillis();
    ChannelFutureListener channelFutureListener = new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture future) throws Exception {
        long responseFlushTime = System.currentTimeMillis() - sendStartTime;
        http2ServerMetrics.responseFlushTime.update(responseFlushTime);
        metrics.updateSendTime(responseFlushTime);
      }
    };
    ctx.channel().writeAndFlush(payloadToSend).addListener(channelFutureListener);
  }

  /**
   * Closes the connection and does not send any response
   */
  @Override
  public void closeConnection(NetworkRequest originalRequest) throws InterruptedException {
    ChannelHandlerContext context = ((NettyServerRequest) originalRequest).getCtx();
    if (context != null) {
      logger.trace("close connection " + context.channel());
      context.channel().close();
    }
  }

  /** Get the next request or block until there is one
   * @return*/
  @Override
  public NetworkRequestBundle receiveRequest() throws InterruptedException {

    NetworkRequestBundle networkRequestBundle;
    NetworkRequest requestToServe;
    Collection<NetworkRequest> requestsToDrop;
    do {
      networkRequestBundle = networkRequestQueue.take();
      NetworkRequest unqueuedRequest;
      while ((unqueuedRequest = unqueuedRequests.poll()) != null) {
        networkRequestBundle.getRequestsToDrop().add(unqueuedRequest);
      }
      requestToServe = networkRequestBundle.getRequestToServe();
      requestsToDrop = networkRequestBundle.getRequestsToDrop();

      if (requestToServe != null) {
        http2ServerMetrics.requestQueuingTime.update(System.currentTimeMillis() - requestToServe.getStartTimeInMs());
        if (!consumeStream(requestToServe)) {
          requestToServe = null;
        }
      }

      Set<NetworkRequest> errorRequests = new HashSet<>();
      for (NetworkRequest requestToDrop : requestsToDrop) {
        http2ServerMetrics.requestQueuingTime.update(System.currentTimeMillis() - requestToDrop.getStartTimeInMs());
        if (!consumeStream(requestToDrop)) {
          errorRequests.add(requestToDrop);
        }
      }
      requestsToDrop.removeAll(errorRequests);
    } while (requestToServe == null && requestsToDrop.isEmpty());

    return new NetworkRequestBundle(requestToServe, requestsToDrop);
  }

  /**
   * Consumes the first 8 bytes of the input stream in the incoming request since TCP implementation uses this size to
   * allocated Buffer.
   * @param request incoming network request.
   * @return false if there was any error while reading the first 8 bytes. Else, return true.
   */
  private boolean consumeStream(NetworkRequest request) throws InterruptedException {
    if (request.equals(EmptyRequest.getInstance())) {
      logger.debug("Request handler {} received shut down command ", request);
    } else {
      DataInputStream stream = new DataInputStream(request.getInputStream());
      try {
        // The first 8 bytes is size of the request. TCP implementation uses this size to allocate buffer. See {@link BoundedReceive}
        // Here we just need to consume it.
        stream.readLong();
      } catch (IOException e) {
        logger.error("Encountered an error while reading length out, close the connection", e);
        closeConnection(request);
        return false;
      }
    }
    return true;
  }

  /**
   * Shuts down the request response channel
   */
  @Override
  public void shutdown() {
    return;
  }
}


