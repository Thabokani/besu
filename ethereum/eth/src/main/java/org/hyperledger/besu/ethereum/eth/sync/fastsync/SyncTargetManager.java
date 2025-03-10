/*
 * Copyright Hyperledger Besu Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.eth.sync.fastsync;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.hyperledger.besu.ethereum.eth.sync.fastsync.PivotBlockRetriever.MAX_QUERY_RETRIES_PER_PEER;
import static org.hyperledger.besu.ethereum.util.LogUtil.throttledLog;

import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.EthPeer;
import org.hyperledger.besu.ethereum.eth.manager.EthPeers;
import org.hyperledger.besu.ethereum.eth.sync.AbstractSyncTargetManager;
import org.hyperledger.besu.ethereum.eth.sync.SynchronizerConfiguration;
import org.hyperledger.besu.ethereum.eth.sync.tasks.RetryingGetHeaderFromPeerByNumberTask;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.messages.DisconnectMessage.DisconnectReason;
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorage;
import org.hyperledger.besu.plugin.services.MetricsSystem;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SyncTargetManager extends AbstractSyncTargetManager {
  private static final Logger LOG = LoggerFactory.getLogger(SyncTargetManager.class);

  private final WorldStateStorage worldStateStorage;
  private final ProtocolSchedule protocolSchedule;
  private final ProtocolContext protocolContext;
  private final EthContext ethContext;
  private final MetricsSystem metricsSystem;
  private final FastSyncState fastSyncState;
  private final AtomicBoolean logDebug = new AtomicBoolean(true);
  private final AtomicBoolean logInfo = new AtomicBoolean(true);
  private final int logDebugRepeatDelay = 15;
  private final int logInfoRepeatDelay = 120;

  public SyncTargetManager(
      final SynchronizerConfiguration config,
      final WorldStateStorage worldStateStorage,
      final ProtocolSchedule protocolSchedule,
      final ProtocolContext protocolContext,
      final EthContext ethContext,
      final MetricsSystem metricsSystem,
      final FastSyncState fastSyncState) {
    super(config, protocolSchedule, protocolContext, ethContext, metricsSystem);
    this.worldStateStorage = worldStateStorage;
    this.protocolSchedule = protocolSchedule;
    this.protocolContext = protocolContext;
    this.ethContext = ethContext;
    this.metricsSystem = metricsSystem;
    this.fastSyncState = fastSyncState;
  }

  @Override
  protected CompletableFuture<Optional<EthPeer>> selectBestAvailableSyncTarget() {
    final BlockHeader pivotBlockHeader = fastSyncState.getPivotBlockHeader().get();
    final EthPeers ethPeers = ethContext.getEthPeers();
    final Optional<EthPeer> maybeBestPeer = ethPeers.bestPeerWithHeightEstimate();
    if (maybeBestPeer.isEmpty()) {
      throttledLog(
          LOG::debug,
          String.format(
              "Unable to find sync target. Currently checking %d peers for usefulness. Pivot block: %d",
              ethContext.getEthPeers().peerCount(), pivotBlockHeader.getNumber()),
          logDebug,
          logDebugRepeatDelay);
      throttledLog(
          LOG::info,
          String.format(
              "Unable to find sync target. Currently checking %d peers for usefulness.",
              ethContext.getEthPeers().peerCount()),
          logInfo,
          logInfoRepeatDelay);
      return completedFuture(Optional.empty());
    } else {
      final EthPeer bestPeer = maybeBestPeer.get();
      if (bestPeer.chainState().getEstimatedHeight() < pivotBlockHeader.getNumber()) {
        LOG.info(
            "Best peer {} has chain height {} below pivotBlock height {}. Waiting for better peers. Current {} of max {}",
            maybeBestPeer.map(EthPeer::getShortNodeId).orElse("none"),
            maybeBestPeer.map(p -> p.chainState().getEstimatedHeight()).orElse(-1L),
            pivotBlockHeader.getNumber(),
            ethPeers.peerCount(),
            ethPeers.getMaxPeers());
        ethPeers.disconnectWorstUselessPeer();
        return completedFuture(Optional.empty());
      } else {
        return confirmPivotBlockHeader(bestPeer);
      }
    }
  }

  private CompletableFuture<Optional<EthPeer>> confirmPivotBlockHeader(final EthPeer bestPeer) {
    final BlockHeader pivotBlockHeader = fastSyncState.getPivotBlockHeader().get();
    final RetryingGetHeaderFromPeerByNumberTask task =
        RetryingGetHeaderFromPeerByNumberTask.forSingleNumber(
            protocolSchedule,
            ethContext,
            metricsSystem,
            pivotBlockHeader.getNumber(),
            MAX_QUERY_RETRIES_PER_PEER);
    task.assignPeer(bestPeer);
    return ethContext
        .getScheduler()
        .timeout(task)
        .thenCompose(
            result -> {
              if (peerHasDifferentPivotBlock(result)) {
                if (!hasPivotChanged(pivotBlockHeader)) {
                  // if the pivot block has not changed, then warn and disconnect this peer
                  LOG.warn(
                      "Best peer has wrong pivot block (#{}) expecting {} but received {}.  Disconnect: {}",
                      pivotBlockHeader.getNumber(),
                      pivotBlockHeader.getHash(),
                      result.size() == 1 ? result.get(0).getHash() : "invalid response",
                      bestPeer);
                  bestPeer.disconnect(DisconnectReason.USELESS_PEER);
                  return CompletableFuture.completedFuture(Optional.<EthPeer>empty());
                }
                LOG.debug(
                    "Retrying best peer {} with new pivot block {}",
                    bestPeer.getShortNodeId(),
                    pivotBlockHeader.toLogString());
                return confirmPivotBlockHeader(bestPeer);
              } else {
                return CompletableFuture.completedFuture(Optional.of(bestPeer));
              }
            })
        .exceptionally(
            error -> {
              LOG.debug("Could not confirm best peer had pivot block", error);
              return Optional.empty();
            });
  }

  private boolean hasPivotChanged(final BlockHeader requestedPivot) {
    return fastSyncState
        .getPivotBlockHash()
        .filter(currentPivotHash -> requestedPivot.getBlockHash().equals(currentPivotHash))
        .isEmpty();
  }

  private boolean peerHasDifferentPivotBlock(final List<BlockHeader> result) {
    final BlockHeader pivotBlockHeader = fastSyncState.getPivotBlockHeader().get();
    return result.size() != 1 || !result.get(0).equals(pivotBlockHeader);
  }

  @Override
  public boolean shouldContinueDownloading() {
    final BlockHeader pivotBlockHeader = fastSyncState.getPivotBlockHeader().get();
    boolean isValidChainHead =
        protocolContext.getBlockchain().getChainHeadHash().equals(pivotBlockHeader.getHash());
    if (!isValidChainHead) {
      if (protocolContext.getBlockchain().contains(pivotBlockHeader.getHash())) {
        protocolContext.getBlockchain().rewindToBlock(pivotBlockHeader.getHash());
      } else {
        return true;
      }
    }
    return !worldStateStorage.isWorldStateAvailable(
        pivotBlockHeader.getStateRoot(), pivotBlockHeader.getBlockHash());
  }
}
