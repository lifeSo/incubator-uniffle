/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.uniffle.client.factory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.uniffle.client.api.CoordinatorClient;
import org.apache.uniffle.client.impl.grpc.CoordinatorGrpcClient;
import org.apache.uniffle.common.ClientType;
import org.apache.uniffle.common.exception.RssException;
import org.apache.uniffle.common.util.JavaUtils;

public class CoordinatorClientFactory {
  private static final Logger LOG = LoggerFactory.getLogger(CoordinatorClientFactory.class);

  private Map<String, Map<String, CoordinatorClient>> clients;

  private CoordinatorClientFactory() {
    clients = JavaUtils.newConcurrentMap();

    Thread thread = Thread.currentThread();
    StackTraceElement[] stackTraceArr = thread.getStackTrace();
    StringBuilder stackTrackStr = new StringBuilder();
    for (StackTraceElement e: stackTraceArr) {
      stackTrackStr.append(e.toString()).append("\n");
    }
    LOG.info("------------------createOrGetCoordinatorClient5555555    call stack track:\n{}\n", stackTrackStr);
  }

  private static class LazyHolder {
    static final CoordinatorClientFactory INSTANCE = new CoordinatorClientFactory();


  }

  public static CoordinatorClientFactory getInstance() {

    Thread thread = Thread.currentThread();
    StackTraceElement[] stackTraceArr = thread.getStackTrace();
    StringBuilder stackTrackStr = new StringBuilder();
    for (StackTraceElement e: stackTraceArr) {
      stackTrackStr.append(e.toString()).append("\n");
    }
    LOG.info("------------------createOrGetCoordinatorClient444444   call stack track:\n{}\n", stackTrackStr);

    return CoordinatorClientFactory.LazyHolder.INSTANCE;
  }

  public synchronized CoordinatorClient createCoordinatorClienttttt(
      ClientType clientType, String host, int port) {


    Thread thread = Thread.currentThread();
    StackTraceElement[] stackTraceArr = thread.getStackTrace();
    StringBuilder stackTrackStr = new StringBuilder();
    for (StackTraceElement e: stackTraceArr) {
      stackTrackStr.append(e.toString()).append("\n");
    }
    LOG.info("------------------createOrGetCoordinatorClient33333   call stack track:\n{}\n", stackTrackStr);

    LOG.info("--------------{} - {} - {} -", clientType, host, port);

    if (clientType.equals(ClientType.GRPC) || clientType.equals(ClientType.GRPC_NETTY)) {
      return new CoordinatorGrpcClient(host, port);
    } else {
      throw new UnsupportedOperationException("Unsupported client type " + clientType);
    }
  }

  public synchronized List<CoordinatorClient> createCoordinatorClienttttt(
      ClientType clientType, String coordinators) {



    Thread thread = Thread.currentThread();
    StackTraceElement[] stackTraceArr = thread.getStackTrace();
    StringBuilder stackTrackStr = new StringBuilder();
    for (StackTraceElement e: stackTraceArr) {
      stackTrackStr.append(e.toString()).append("\n");
    }
    LOG.info("------------------createOrGetCoordinatorClient111122222    call stack track:\n{}\n", stackTrackStr);

    LOG.info("---------------");
    LOG.info("Start to create coordinator clients from {}", coordinators);

    String[] coordinatorList = coordinators.trim().split(",");
    if (coordinatorList.length == 0) {
      String msg = "Invalid " + coordinators;
      LOG.error(msg);
      throw new RssException(msg);
    }

    List<CoordinatorClient> coordinatorClients = Lists.newLinkedList();
    for (String coordinator : coordinatorList) {
      String[] ipPort = coordinator.trim().split(":");
      if (ipPort.length != 2) {
        String msg = "Invalid coordinator format " + Arrays.toString(ipPort);
        LOG.error(msg);
        throw new RssException(msg);
      }

      String host = ipPort[0];
      int port = Integer.parseInt(ipPort[1]);
      CoordinatorClient coordinatorClient = createOrGetCoordinatorClient(clientType, host, port);
      coordinatorClients.add(coordinatorClient);
      LOG.info("Add coordinator client {}", coordinatorClient.getDesc());
    }
    LOG.info(
        "Finish create coordinator clients {}",
        coordinatorClients.stream()
            .map(CoordinatorClient::getDesc)
            .collect(Collectors.joining(", ")));
    return coordinatorClients;
  }

  private synchronized CoordinatorClient createOrGetCoordinatorClient(
      ClientType clientType, String host, int port) {

    Thread thread = Thread.currentThread();
    StackTraceElement[] stackTraceArr = thread.getStackTrace();
    StringBuilder stackTrackStr = new StringBuilder();
    for (StackTraceElement e: stackTraceArr) {
      stackTrackStr.append(e.toString()).append("\n");
    }
    LOG.info("------------------createOrGetCoordinatorClient1111    call stack track:\n{}\n", stackTrackStr);

    String hostPort = host + ":" + port;
    clients.putIfAbsent(clientType.toString(), JavaUtils.newConcurrentMap());
    Map<String, CoordinatorClient> hostToClients = clients.get(clientType.toString());
    if (hostToClients.get(hostPort) == null) {
      hostToClients.put(hostPort, createCoordinatorClienttttt(clientType, host, port));
    }
    return hostToClients.get(hostPort);
  }
}
