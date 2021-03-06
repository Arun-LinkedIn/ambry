/*
 * Copyright 2020 LinkedIn Corp. All rights reserved.
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
 *
 */

package com.github.ambry.network;

import com.github.ambry.clustermap.DataNodeId;
import com.github.ambry.clustermap.MockReplicaId;
import com.github.ambry.clustermap.ReplicaType;
import com.github.ambry.utils.TestUtils;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;


/**
 * Test {@link CompositeNetworkClient} and {@link CompositeNetworkClientFactory}.
 */
public class CompositeNetworkClientTest {
  private final Mocks diskMocks = new Mocks();
  private final Mocks cloudMocks = new Mocks();
  private final CompositeNetworkClientFactory factory;
  private int nextCorrelationId = 0;

  public CompositeNetworkClientTest() {
    Map<ReplicaType, NetworkClientFactory> childFactories = new HashMap<>();
    childFactories.put(ReplicaType.DISK_BACKED, diskMocks.factory);
    childFactories.put(ReplicaType.CLOUD_BACKED, cloudMocks.factory);
    factory = new CompositeNetworkClientFactory(childFactories);
  }

  /**
   * Test {@link CompositeNetworkClientFactory}
   */
  @Test
  public void testFactory() throws IOException {
    CompositeNetworkClient compositeClientOne = (CompositeNetworkClient) factory.getNetworkClient();
    CompositeNetworkClient compositeClientTwo = (CompositeNetworkClient) factory.getNetworkClient();
    assertNotNull("Client is null", compositeClientOne);
    assertNotNull("Client is null", compositeClientTwo);
    verify(diskMocks.factory, times(2)).getNetworkClient();
    verify(cloudMocks.factory, times(2)).getNetworkClient();
  }

  /**
   * Test {@link CompositeNetworkClient#warmUpConnections}
   */
  @Test
  public void testWarmUp() throws IOException {
    NetworkClient compositeClient = factory.getNetworkClient();
    int numDiskNodesWarmedUp = 25;
    int numCloudNodesWarmedUp = 17;
    when(diskMocks.client.warmUpConnections(any(), anyInt(), anyLong(), any())).thenReturn(numDiskNodesWarmedUp);
    when(cloudMocks.client.warmUpConnections(any(), anyInt(), anyLong(), any())).thenReturn(numCloudNodesWarmedUp);
    List<DataNodeId> nodesToWarmUp = Collections.nCopies(3, mock(DataNodeId.class));
    assertEquals("Unexpected number of connections warmed up", numDiskNodesWarmedUp + numCloudNodesWarmedUp,
        compositeClient.warmUpConnections(nodesToWarmUp, 100, 1000, Collections.emptyList()));
    verify(diskMocks.client).warmUpConnections(nodesToWarmUp, 100, 1000, Collections.emptyList());
    verify(cloudMocks.client).warmUpConnections(nodesToWarmUp, 100, 1000, Collections.emptyList());
  }

  /**
   * Test {@link CompositeNetworkClient#sendAndPoll}
   */
  @Test
  public void testSendAndPoll() throws IOException {
    NetworkClient compositeClient = factory.getNetworkClient();

    // a couple for the first client and one request for the second client
    List<RequestInfo> requestsToSendOne =
        Arrays.asList(getRequestInfo(ReplicaType.DISK_BACKED), getRequestInfo(ReplicaType.CLOUD_BACKED),
            getRequestInfo(ReplicaType.DISK_BACKED));
    // try dropping unknown correlation ID, this should not cause issues
    Set<Integer> requestsToDrop = Collections.singleton(77);
    int pollTimeoutMs = 1000;
    List<ResponseInfo> diskResponses =
        Collections.singletonList(new ResponseInfo(requestsToSendOne.get(0), null, Unpooled.EMPTY_BUFFER));
    List<ResponseInfo> cloudResponses = Collections.emptyList();
    when(diskMocks.client.sendAndPoll(any(), any(), anyInt())).thenReturn(diskResponses);
    when(cloudMocks.client.sendAndPoll(any(), any(), anyInt())).thenReturn(cloudResponses);
    List<ResponseInfo> responses = compositeClient.sendAndPoll(requestsToSendOne, requestsToDrop, pollTimeoutMs);
    assertEquals("Unexpected response list", diskResponses, responses);
    verify(diskMocks.client).sendAndPoll(Arrays.asList(requestsToSendOne.get(0), requestsToSendOne.get(2)),
        Collections.emptySet(), pollTimeoutMs);
    verify(cloudMocks.client).sendAndPoll(Collections.singletonList(requestsToSendOne.get(1)), Collections.emptySet(),
        pollTimeoutMs);
    verifyNoMoreInteractions(diskMocks.client);
    verifyNoMoreInteractions(cloudMocks.client);

    diskMocks.resetMocks();
    cloudMocks.resetMocks();
    // two requests for the second client
    List<RequestInfo> requestsToSendTwo =
        Arrays.asList(getRequestInfo(ReplicaType.CLOUD_BACKED), getRequestInfo(ReplicaType.CLOUD_BACKED));
    // drop one request from the current iteration and one from the last
    requestsToDrop = Stream.of(requestsToSendOne.get(2), requestsToSendTwo.get(0))
        .map(r -> r.getRequest().getCorrelationId())
        .collect(Collectors.toSet());
    diskResponses = Collections.singletonList(new ResponseInfo(requestsToSendOne.get(2), null, Unpooled.EMPTY_BUFFER));
    cloudResponses = Collections.singletonList(new ResponseInfo(requestsToSendTwo.get(1), null, Unpooled.EMPTY_BUFFER));
    when(diskMocks.client.sendAndPoll(any(), any(), anyInt())).thenReturn(diskResponses);
    when(cloudMocks.client.sendAndPoll(any(), any(), anyInt())).thenReturn(cloudResponses);
    responses = compositeClient.sendAndPoll(requestsToSendTwo, requestsToDrop, pollTimeoutMs);
    assertEquals("Unexpected response list",
        Stream.of(diskResponses, cloudResponses).flatMap(List::stream).collect(Collectors.toList()), responses);
    verify(diskMocks.client).sendAndPoll(Collections.emptyList(),
        Collections.singleton(requestsToSendOne.get(2).getRequest().getCorrelationId()), pollTimeoutMs);
    verify(cloudMocks.client).sendAndPoll(requestsToSendTwo,
        Collections.singleton(requestsToSendTwo.get(0).getRequest().getCorrelationId()), pollTimeoutMs);
    verifyNoMoreInteractions(diskMocks.client);
    verifyNoMoreInteractions(cloudMocks.client);
  }

  /**
   * Test {@link CompositeNetworkClient#wakeup}
   */
  @Test
  public void testWakeup() throws IOException {
    NetworkClient compositeClient = factory.getNetworkClient();
    compositeClient.wakeup();
    verify(diskMocks.client).wakeup();
    verify(cloudMocks.client).wakeup();
  }

  /**
   * Test {@link CompositeNetworkClient#close}
   */
  @Test
  public void testClose() throws IOException {
    NetworkClient compositeClient = factory.getNetworkClient();
    compositeClient.close();
    verify(diskMocks.client).close();
    verify(cloudMocks.client).close();
  }

  /**
   * Test when a child client is not configured for a type of replica
   */
  @Test
  public void testReplicaTypeNotFound() throws Exception {
    NetworkClient compositeClient = new CompositeNetworkClient(new EnumMap<>(ReplicaType.class));
    TestUtils.assertException(IllegalStateException.class,
        () -> compositeClient.sendAndPoll(Collections.singletonList(getRequestInfo(ReplicaType.CLOUD_BACKED)),
            Collections.emptySet(), 1000), null);
  }

  /**
   * @param replicaType the {@link ReplicaType} for the request.
   * @return a new {@link RequestInfo} with a new correlation ID.
   */
  private RequestInfo getRequestInfo(ReplicaType replicaType) {
    return new RequestInfo("a", new Port(1, PortType.SSL), new MockSend(nextCorrelationId++),
        new MockReplicaId(replicaType));
  }

  private static class Mocks {
    private final NetworkClientFactory factory;
    private final NetworkClient client;

    Mocks() {
      factory = mock(NetworkClientFactory.class);
      client = mock(NetworkClient.class);
      resetMocks();
    }

    private void resetMocks() {
      try {
        reset(factory, client);
        when(factory.getNetworkClient()).thenReturn(client);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
