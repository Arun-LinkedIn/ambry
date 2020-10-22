/*
 * Copyright 2017 LinkedIn Corp. All rights reserved.
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
package com.github.ambry.account;

import com.github.ambry.config.AccountServiceConfig;
import com.github.ambry.server.StatsSnapshot;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Class which provides a basic implementation an {@link AccountService}. The handling of accounts is mostly the same
 * for all the implementations. The only thing the implementations (usually) differ in is what the source is of the
 * accounts (Zookeeper / Helix, MySql, local JSON file, etcd, Consul, etc).
 */
abstract class AbstractAccountService implements AccountService {

  private static final Logger logger = LoggerFactory.getLogger(AbstractAccountService.class);

  protected final AtomicReference<AccountInfoMap> accountInfoMapRef;
  protected final ReentrantLock lock = new ReentrantLock();
  protected final CopyOnWriteArraySet<Consumer<Collection<Account>>> accountUpdateConsumers =
      new CopyOnWriteArraySet<>();
  protected final AccountServiceMetrics accountServiceMetrics;
  private final AccountServiceConfig config;

  public AbstractAccountService(AccountServiceConfig config, AccountServiceMetrics accountServiceMetrics) {
    this.config = config;
    this.accountServiceMetrics = accountServiceMetrics;

    this.accountInfoMapRef = new AtomicReference<>(new AccountInfoMap(accountServiceMetrics));
  }

  /**
   * Ensures the account service is ready to process requests. Throws an unchecked exception (for example
   * {@link IllegalStateException}) if the account service is not ready to process requests.
   */
  abstract protected void checkOpen();

  @Override
  public Account getAccountByName(String accountName) {
    checkOpen();
    Objects.requireNonNull(accountName, "accountName cannot be null.");
    return accountInfoMapRef.get().getAccountByName(accountName);
  }

  @Override
  public Account getAccountById(short id) {
    checkOpen();
    return accountInfoMapRef.get().getAccountById(id);
  }

  @Override
  public Collection<Account> getAllAccounts() {
    checkOpen();
    return accountInfoMapRef.get().getAccounts();
  }

  @Override
  public Collection<Container> updateContainers(String accountName, Collection<Container> containers)
      throws AccountServiceException {
    checkOpen();
    // input validation
    if (accountName == null || accountName.isEmpty() || containers == null || containers.isEmpty()) {
      throw new AccountServiceException("Account or container is null or empty", AccountServiceErrorCode.BadRequest);
    }
    Account account = getAccountByName(accountName);
    if (account == null) {
      logger.error("Account {} is not found", accountName);
      throw new AccountServiceException("Account " + accountName + " is not found", AccountServiceErrorCode.NotFound);
    }

    List<Container> resolvedContainers = new ArrayList<>();
    List<Container> existingUnchangedContainers = new ArrayList<>();
    // create a hashmap to map the name to existing containers in account
    Map<String, Container> existingContainersInAccount = new HashMap<>();
    account.getAllContainers().forEach(c -> existingContainersInAccount.put(c.getName(), c));

    // Generate container ids for new containers
    short nextContainerId = account.getAllContainers()
        .stream()
        .map(Container::getId)
        .max(Short::compareTo)
        .map(maxId -> (short) (maxId + 1))
        .orElse(config.containerIdStartNumber);

    for (Container container : containers) {
      if (container.getId() == Container.UNKNOWN_CONTAINER_ID) {
        // new container
        // make sure there is no conflicting container (conflicting means a container with same name but different attributes already exists).
        Container existingContainer = existingContainersInAccount.get(container.getName());
        if (existingContainer != null) {
          if (existingContainer.isSameContainer(container)) {
            // If an exactly same container already exists, treat as no-op (may be retry after partial failure).
            // But include it in the list returned to caller to provide the containerId.
            logger.info("Request to create container with existing name and properties: {}",
                existingContainer.toJson().toString());
            existingUnchangedContainers.add(existingContainer);
          } else {
            throw new AccountServiceException("There is a conflicting container in account " + accountName,
                AccountServiceErrorCode.ResourceConflict);
          }
        } else {
          resolvedContainers.add(
              new ContainerBuilder(container).setId(nextContainerId).setParentAccountId(account.getId()).build());
          ++nextContainerId;
        }
      } else {
        // existing container
        Container existingContainer = existingContainersInAccount.get(container.getName());
        if (existingContainer == null) {
          throw new AccountServiceException(
              "In account " + accountName + ", container " + container.getName() + " does not exist (containerId "
                  + container.getId() + " was supplied)", AccountServiceErrorCode.NotFound);
        } else if (existingContainer.getId() != container.getId()) {
          throw new AccountServiceException(
              "In account " + accountName + ", container " + container.getName() + " has containerId "
                  + existingContainer.getId() + " (" + container.getId() + " was supplied)",
              AccountServiceErrorCode.ResourceConflict);
        } else if (existingContainer.getSnapshotVersion() != container.getSnapshotVersion()) {
          throw new AccountServiceException(
              "In account " + accountName + ", container " + container.getName() + " has version "
                  + existingContainer.getSnapshotVersion() + " (" + container.getSnapshotVersion() + " was supplied)",
              AccountServiceErrorCode.ResourceConflict);
        } else {
          resolvedContainers.add(container);
        }
      }
    }

    if (!resolvedContainers.isEmpty()) {
      updateResolvedContainers(account, resolvedContainers);
    }

    resolvedContainers.addAll(existingUnchangedContainers);
    return resolvedContainers;
  }

  /**
   * Update the containers that have been vetted as non-duplicates and populated with ids.
   * @param account the account owning the containers.
   * @param resolvedContainers the resolved containers.
   * @throws AccountServiceException
   */
  protected void updateResolvedContainers(Account account, Collection<Container> resolvedContainers)
      throws AccountServiceException {
    // In case updating account metadata store failed, we do a deep copy of original account. Thus, we don't have to
    // revert changes in original account when there is a failure.
    AccountBuilder accountBuilder = new AccountBuilder(account);
    resolvedContainers.forEach(accountBuilder::addOrUpdateContainer);
    Account updatedAccount = accountBuilder.build();
    updateAccounts(Collections.singletonList(updatedAccount));
  }

  @Override
  public boolean addAccountUpdateConsumer(Consumer<Collection<Account>> accountUpdateConsumer) {
    checkOpen();
    Objects.requireNonNull(accountUpdateConsumer, "accountUpdateConsumer to subscribe cannot be null");
    return accountUpdateConsumers.add(accountUpdateConsumer);
  }

  @Override
  public boolean removeAccountUpdateConsumer(Consumer<Collection<Account>> accountUpdateConsumer) {
    checkOpen();
    Objects.requireNonNull(accountUpdateConsumer, "accountUpdateConsumer to unsubscribe cannot be null");
    return accountUpdateConsumers.remove(accountUpdateConsumer);
  }

  /**
   * Logs and notifies account update {@link Consumer}s about any new account changes/creations.
   * @param updatedAccounts collection of updated accounts
   * @param isCalledFromListener {@code true} if the caller is the account update listener, {@code false} otherwise.
   */
  protected void notifyAccountUpdateConsumers(Collection<Account> updatedAccounts, boolean isCalledFromListener) {
    if (updatedAccounts.size() > 0) {
      logger.info("Received updates for {} accounts. Received from listener={}. Account IDs={}", updatedAccounts.size(),
          isCalledFromListener, updatedAccounts.stream().map(Account::getId).collect(Collectors.toList()));
      // @todo In long run, this metric is not necessary.
      if (isCalledFromListener) {
        accountServiceMetrics.accountUpdatesCapturedByScheduledUpdaterCount.inc();
      }
      for (Consumer<Collection<Account>> accountUpdateConsumer : accountUpdateConsumers) {
        long startTime = System.currentTimeMillis();
        try {
          accountUpdateConsumer.accept(Collections.unmodifiableCollection(updatedAccounts));
          long consumerExecutionTimeInMs = System.currentTimeMillis() - startTime;
          logger.trace("Consumer={} has been notified for account change, took {} ms", accountUpdateConsumer,
              consumerExecutionTimeInMs);
          accountServiceMetrics.accountUpdateConsumerTimeInMs.update(consumerExecutionTimeInMs);
        } catch (Exception e) {
          logger.error("Exception occurred when notifying accountUpdateConsumer={}", accountUpdateConsumer, e);
        }
      }
    } else {
      logger.debug("HelixAccountService is updated with 0 updated account");
    }
  }

  /**
   * Selects {@link Container}s to be marked as INACTIVE and marked in underlying account store.
   */
  public void selectInactiveContainersAndMarkInStore(StatsSnapshot statsSnapshot) {
    Set<Container> inactiveContainerCandidateSet = AccountUtils.selectInactiveContainerCandidates(statsSnapshot,
        getContainersByStatus(Container.ContainerStatus.DELETE_IN_PROGRESS));
    try {
      markContainersInactive(inactiveContainerCandidateSet);
    } catch (InterruptedException e) {
      logger.error("Mark inactive container in zookeeper is interrupted", e);
    }
  }

  /**
   * Mark the given {@link Container}s status to INACTIVE in account store.
   * @param inactiveContainerCandidateSet DELETE_IN_PROGRESS {@link Container} set which has been deleted successfully during compaction.
   */
  void markContainersInactive(Set<Container> inactiveContainerCandidateSet) throws InterruptedException {
    if (inactiveContainerCandidateSet != null) {
      boolean success = false;
      Exception updateException = null;
      int retry = 0;
      while (!success && retry < config.maxRetryCountOnUpdateFailure) {
        Map<Short, Account> accountToUpdateMap = new HashMap<>();
        inactiveContainerCandidateSet.forEach(container -> {
          // start by getting account, and then get container from account to make sure that we are editing the most
          // recent snapshot
          short accountId = container.getParentAccountId();
          Account accountToEdit = accountToUpdateMap.computeIfAbsent(accountId, this::getAccountById);
          Container containerToEdit = accountToEdit.getContainerById(container.getId());
          Container editedContainer =
              new ContainerBuilder(containerToEdit).setStatus(Container.ContainerStatus.INACTIVE).build();
          accountToUpdateMap.put(accountId,
              new AccountBuilder(accountToEdit).addOrUpdateContainer(editedContainer).build());
        });
        try {
          updateAccounts(accountToUpdateMap.values());
          success = true;
        } catch (AccountServiceException ase) {
          updateException = ase;
          retry++;
          Thread.sleep(config.retryDelayMs);
        }
      }
      if (updateException != null) {
        logger.error("Failed to mark containers INACTIVE in set : {}  after {} retries", inactiveContainerCandidateSet,
            config.maxRetryCountOnUpdateFailure, updateException);
        accountServiceMetrics.accountUpdatesToStoreErrorCount.inc();
      }
    }
  }
}

