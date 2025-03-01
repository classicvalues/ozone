/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.hadoop.ozone.container.keyvalue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.hdds.conf.ConfigurationSource;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ContainerDataProto;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ContainerType;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.ContainerReplicaProto;
import org.apache.hadoop.hdds.scm.container.common.helpers.StorageContainerException;
import org.apache.hadoop.hdfs.util.Canceler;
import org.apache.hadoop.hdfs.util.DataTransferThrottler;
import org.apache.hadoop.io.nativeio.NativeIO;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.container.common.helpers.ContainerUtils;
import org.apache.hadoop.ozone.container.common.impl.ContainerDataYaml;
import org.apache.hadoop.ozone.container.common.interfaces.Container;
import org.apache.hadoop.ozone.container.common.interfaces.ContainerPacker;
import org.apache.hadoop.ozone.container.common.interfaces.VolumeChoosingPolicy;
import org.apache.hadoop.ozone.container.common.utils.ReferenceCountedDB;
import org.apache.hadoop.ozone.container.common.utils.StorageVolumeUtil;
import org.apache.hadoop.ozone.container.common.volume.HddsVolume;
import org.apache.hadoop.ozone.container.common.volume.VolumeSet;
import org.apache.hadoop.ozone.container.keyvalue.helpers.BlockUtils;
import org.apache.hadoop.ozone.container.keyvalue.helpers.KeyValueContainerLocationUtil;
import org.apache.hadoop.ozone.container.keyvalue.helpers.KeyValueContainerUtil;
import org.apache.hadoop.ozone.container.upgrade.VersionedDatanodeFeatures;
import org.apache.hadoop.util.DiskChecker.DiskOutOfSpaceException;

import com.google.common.base.Preconditions;
import org.apache.commons.io.FileUtils;
import static org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.Result.CONTAINER_ALREADY_EXISTS;
import static org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.Result.CONTAINER_FILES_CREATE_ERROR;
import static org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.Result.CONTAINER_INTERNAL_ERROR;
import static org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.Result.CONTAINER_NOT_OPEN;
import static org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.Result.DISK_OUT_OF_SPACE;
import static org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.Result.ERROR_IN_COMPACT_DB;
import static org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.Result.ERROR_IN_DB_SYNC;
import static org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.Result.INVALID_CONTAINER_STATE;
import static org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.Result.UNSUPPORTED_REQUEST;
import static org.apache.hadoop.ozone.container.common.utils.StorageVolumeUtil.onFailure;

import org.apache.hadoop.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class to perform KeyValue Container operations. Any modifications to
 * KeyValueContainer object should ideally be done via api exposed in
 * KeyValueHandler class.
 */
public class KeyValueContainer implements Container<KeyValueContainerData> {

  private static final Logger LOG =
          LoggerFactory.getLogger(KeyValueContainer.class);

  // Use a non-fair RW lock for better throughput, we may revisit this decision
  // if this causes fairness issues.
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

  private final KeyValueContainerData containerData;
  private ConfigurationSource config;

  public KeyValueContainer(KeyValueContainerData containerData,
      ConfigurationSource
      ozoneConfig) {
    Preconditions.checkNotNull(containerData,
            "KeyValueContainerData cannot be null");
    Preconditions.checkNotNull(ozoneConfig,
            "Ozone configuration cannot be null");
    this.config = ozoneConfig;
    this.containerData = containerData;
  }

  @Override
  public void create(VolumeSet volumeSet, VolumeChoosingPolicy
      volumeChoosingPolicy, String clusterId) throws StorageContainerException {
    Preconditions.checkNotNull(volumeChoosingPolicy, "VolumeChoosingPolicy " +
        "cannot be null");
    Preconditions.checkNotNull(volumeSet, "VolumeSet cannot be null");
    Preconditions.checkNotNull(clusterId, "clusterId cannot be null");

    File containerMetaDataPath = null;
    //acquiring volumeset read lock
    long maxSize = containerData.getMaxSize();
    volumeSet.readLock();
    try {
      HddsVolume containerVolume = volumeChoosingPolicy.chooseVolume(
          StorageVolumeUtil.getHddsVolumesList(volumeSet.getVolumesList()),
          maxSize);
      String hddsVolumeDir = containerVolume.getHddsRootDir().toString();

      long containerID = containerData.getContainerID();
      String idDir = VersionedDatanodeFeatures.ScmHA.chooseContainerPathID(
              containerVolume, clusterId);

      containerMetaDataPath = KeyValueContainerLocationUtil
          .getContainerMetaDataPath(hddsVolumeDir, idDir, containerID);
      containerData.setMetadataPath(containerMetaDataPath.getPath());

      File chunksPath = KeyValueContainerLocationUtil.getChunksLocationPath(
          hddsVolumeDir, idDir, containerID);

      // Check if it is new Container.
      ContainerUtils.verifyIsNewContainer(containerMetaDataPath);

      //Create Metadata path chunks path and metadata db
      File dbFile = getContainerDBFile();

      containerData.setSchemaVersion(
          VersionedDatanodeFeatures.SchemaV2.chooseSchemaVersion());
      KeyValueContainerUtil.createContainerMetaData(containerID,
              containerMetaDataPath, chunksPath, dbFile,
              containerData.getSchemaVersion(), config);

      //Set containerData for the KeyValueContainer.
      containerData.setChunksPath(chunksPath.getPath());
      containerData.setDbFile(dbFile);
      containerData.setVolume(containerVolume);

      // Create .container file
      File containerFile = getContainerFile();
      createContainerFile(containerFile);

    } catch (StorageContainerException ex) {
      if (containerMetaDataPath != null && containerMetaDataPath.getParentFile()
          .exists()) {
        FileUtil.fullyDelete(containerMetaDataPath.getParentFile());
      }
      throw ex;
    } catch (DiskOutOfSpaceException ex) {
      throw new StorageContainerException("Container creation failed, due to " +
          "disk out of space", ex, DISK_OUT_OF_SPACE);
    } catch (FileAlreadyExistsException ex) {
      throw new StorageContainerException("Container creation failed because " +
          "ContainerFile already exists", ex, CONTAINER_ALREADY_EXISTS);
    } catch (IOException ex) {
      if (containerMetaDataPath != null && containerMetaDataPath.getParentFile()
          .exists()) {
        FileUtil.fullyDelete(containerMetaDataPath.getParentFile());
      }

      throw new StorageContainerException(
          "Container creation failed. " + ex.getMessage(), ex,
          CONTAINER_INTERNAL_ERROR);
    } finally {
      volumeSet.readUnlock();
    }
  }

  /**
   * Set all of the path realted container data fields based on the name
   * conventions.
   *
   * @param clusterId
   * @param containerVolume
   * @param hddsVolumeDir
   */
  public void populatePathFields(String clusterId,
      HddsVolume containerVolume, String hddsVolumeDir) {

    long containerId = containerData.getContainerID();

    File containerMetaDataPath = KeyValueContainerLocationUtil
        .getContainerMetaDataPath(hddsVolumeDir, clusterId, containerId);

    File chunksPath = KeyValueContainerLocationUtil.getChunksLocationPath(
        hddsVolumeDir, clusterId, containerId);
    File dbFile = KeyValueContainerLocationUtil.getContainerDBFile(
        containerMetaDataPath, containerId);

    //Set containerData for the KeyValueContainer.
    containerData.setMetadataPath(containerMetaDataPath.getPath());
    containerData.setChunksPath(chunksPath.getPath());
    containerData.setDbFile(dbFile);
    containerData.setVolume(containerVolume);
  }

  /**
   * Writes to .container file.
   *
   * @param containerFile container file name
   * @param isCreate True if creating a new file. False is updating an
   *                 existing container file.
   * @throws StorageContainerException
   */
  private void writeToContainerFile(File containerFile, boolean isCreate)
      throws StorageContainerException {
    File tempContainerFile = null;
    long containerId = containerData.getContainerID();
    try {
      tempContainerFile = createTempFile(containerFile);
      ContainerDataYaml.createContainerFile(
          ContainerType.KeyValueContainer, containerData, tempContainerFile);

      // NativeIO.renameTo is an atomic function. But it might fail if the
      // container file already exists. Hence, we handle the two cases
      // separately.
      if (isCreate) {
        NativeIO.renameTo(tempContainerFile, containerFile);
      } else {
        Files.move(tempContainerFile.toPath(), containerFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING);
      }

    } catch (IOException ex) {
      onFailure(containerData.getVolume());
      throw new StorageContainerException("Error while creating/ updating " +
          ".container file. ContainerID: " + containerId, ex,
          CONTAINER_FILES_CREATE_ERROR);
    } finally {
      if (tempContainerFile != null && tempContainerFile.exists()) {
        if (!tempContainerFile.delete()) {
          LOG.warn("Unable to delete container temporary file: {}.",
              tempContainerFile.getAbsolutePath());
        }
      }
    }
  }

  private void createContainerFile(File containerFile)
      throws StorageContainerException {
    writeToContainerFile(containerFile, true);
  }

  private void updateContainerFile(File containerFile)
      throws StorageContainerException {
    writeToContainerFile(containerFile, false);
  }


  @Override
  public void delete() throws StorageContainerException {
    long containerId = containerData.getContainerID();
    try {
      KeyValueContainerUtil.removeContainer(containerData, config);
    } catch (StorageContainerException ex) {
      throw ex;
    } catch (IOException ex) {
      // TODO : An I/O error during delete can leave partial artifacts on the
      // disk. We will need the cleaner thread to cleanup this information.
      onFailure(containerData.getVolume());
      String errMsg = String.format("Failed to cleanup container. ID: %d",
          containerId);
      LOG.error(errMsg, ex);
      throw new StorageContainerException(errMsg, ex, CONTAINER_INTERNAL_ERROR);
    }
  }

  @Override
  public void markContainerForClose() throws StorageContainerException {
    writeLock();
    try {
      if (getContainerState() != ContainerDataProto.State.OPEN) {
        throw new StorageContainerException(
            "Attempting to close a " + getContainerState() + " container.",
            CONTAINER_NOT_OPEN);
      }
      updateContainerData(() ->
          containerData.setState(ContainerDataProto.State.CLOSING));
    } finally {
      writeUnlock();
    }
  }

  @Override
  public void markContainerUnhealthy() throws StorageContainerException {
    writeLock();
    try {
      updateContainerData(() ->
          containerData.setState(ContainerDataProto.State.UNHEALTHY));
    } finally {
      writeUnlock();
    }
    LOG.warn("Moving container {} to state UNHEALTHY from state:{} Trace:{}",
            containerData.getContainerPath(), containerData.getState(),
            StringUtils.getStackTrace(Thread.currentThread()));
  }

  @Override
  public void quasiClose() throws StorageContainerException {
    // The DB must be synced during close operation
    flushAndSyncDB();

    writeLock();
    try {
      // Second sync should be a very light operation as sync has already
      // been done outside the lock.
      flushAndSyncDB();
      updateContainerData(containerData::quasiCloseContainer);
    } finally {
      writeUnlock();
    }
  }

  @Override
  public void close() throws StorageContainerException {
    // The DB must be synced during close operation
    flushAndSyncDB();

    writeLock();
    try {
      // Second sync should be a very light operation as sync has already
      // been done outside the lock.
      flushAndSyncDB();
      updateContainerData(containerData::closeContainer);
    } finally {
      writeUnlock();
    }
    LOG.info("Container {} is closed with bcsId {}.",
        containerData.getContainerID(),
        containerData.getBlockCommitSequenceId());
  }

  @Override
  public void updateDataScanTimestamp(Instant time)
      throws StorageContainerException {
    writeLock();
    try {
      updateContainerData(() -> containerData.updateDataScanTime(time));
    } finally {
      writeUnlock();
    }
  }

  /**
   *
   * Must be invoked with the writeLock held.
   *
   * @param update
   * @throws StorageContainerException
   */
  private void updateContainerData(Runnable update)
      throws StorageContainerException {
    Preconditions.checkState(hasWriteLock());
    ContainerDataProto.State oldState = null;
    try {
      oldState = containerData.getState();
      update.run();
      File containerFile = getContainerFile();
      // update the new container data to .container File
      updateContainerFile(containerFile);

    } catch (StorageContainerException ex) {
      if (oldState != null
          && containerData.getState() != ContainerDataProto.State.UNHEALTHY) {
        // Failed to update .container file. Reset the state to old state only
        // if the current state is not unhealthy.
        containerData.setState(oldState);
      }
      throw ex;
    }
  }

  private void compactDB() throws StorageContainerException {
    try {
      try(ReferenceCountedDB db = BlockUtils.getDB(containerData, config)) {
        db.getStore().compactDB();
      }
    } catch (StorageContainerException ex) {
      throw ex;
    } catch (IOException ex) {
      LOG.error("Error in DB compaction while closing container", ex);
      onFailure(containerData.getVolume());
      throw new StorageContainerException(ex, ERROR_IN_COMPACT_DB);
    }
  }

  private void flushAndSyncDB() throws StorageContainerException {
    try {
      try (ReferenceCountedDB db = BlockUtils.getDB(containerData, config)) {
        db.getStore().flushLog(true);
        LOG.info("Container {} is synced with bcsId {}.",
            containerData.getContainerID(),
            containerData.getBlockCommitSequenceId());
      }
    } catch (StorageContainerException ex) {
      throw ex;
    } catch (IOException ex) {
      LOG.error("Error in DB sync while closing container", ex);
      onFailure(containerData.getVolume());
      throw new StorageContainerException(ex, ERROR_IN_DB_SYNC);
    }
  }

  @Override
  public KeyValueContainerData getContainerData()  {
    return containerData;
  }

  @Override
  public ContainerProtos.ContainerDataProto.State getContainerState() {
    return containerData.getState();
  }

  @Override
  public ContainerType getContainerType() {
    return ContainerType.KeyValueContainer;
  }

  @Override
  public void update(
      Map<String, String> metadata, boolean forceUpdate)
      throws StorageContainerException {

    // TODO: Now, when writing the updated data to .container file, we are
    // holding lock and writing data to disk. We can have async implementation
    // to flush the update container data to disk.
    long containerId = containerData.getContainerID();
    if(!containerData.isValid()) {
      LOG.debug("Invalid container data. ContainerID: {}", containerId);
      throw new StorageContainerException("Invalid container data. " +
          "ContainerID: " + containerId, INVALID_CONTAINER_STATE);
    }
    if (!forceUpdate && !containerData.isOpen()) {
      throw new StorageContainerException(
          "Updating a closed container without force option is not allowed. " +
              "ContainerID: " + containerId, UNSUPPORTED_REQUEST);
    }

    Map<String, String> oldMetadata = containerData.getMetadata();
    try {
      writeLock();
      for (Map.Entry<String, String> entry : metadata.entrySet()) {
        containerData.addMetadata(entry.getKey(), entry.getValue());
      }

      File containerFile = getContainerFile();
      // update the new container data to .container File
      updateContainerFile(containerFile);
    } catch (StorageContainerException  ex) {
      containerData.setMetadata(oldMetadata);
      throw ex;
    } finally {
      writeUnlock();
    }
  }

  @Override
  public void updateDeleteTransactionId(long deleteTransactionId) {
    containerData.updateDeleteTransactionId(deleteTransactionId);
  }

  @Override
  public void importContainerData(InputStream input,
      ContainerPacker<KeyValueContainerData> packer) throws IOException {
    writeLock();
    try {
      if (getContainerFile().exists()) {
        String errorMessage = String.format(
            "Can't import container (cid=%d) data to a specific location"
                + " as the container descriptor (%s) has already been exist.",
            getContainerData().getContainerID(),
            getContainerFile().getAbsolutePath());
        throw new StorageContainerException(errorMessage,
            CONTAINER_ALREADY_EXISTS);
      }
      //copy the values from the input stream to the final destination
      // directory.
      byte[] descriptorContent = packer.unpackContainerData(this, input);

      Preconditions.checkNotNull(descriptorContent,
          "Container descriptor is missing from the container archive: "
              + getContainerData().getContainerID());

      //now, we have extracted the container descriptor from the previous
      //datanode. We can load it and upload it with the current data
      // (original metadata + current filepath fields)
      KeyValueContainerData originalContainerData =
          (KeyValueContainerData) ContainerDataYaml
              .readContainer(descriptorContent);


      containerData.setState(originalContainerData.getState());
      containerData
          .setContainerDBType(originalContainerData.getContainerDBType());
      containerData.setSchemaVersion(originalContainerData.getSchemaVersion());

      //rewriting the yaml file with new checksum calculation.
      update(originalContainerData.getMetadata(), true);

      //fill in memory stat counter (keycount, byte usage)
      KeyValueContainerUtil.parseKVContainerData(containerData, config);

    } catch (Exception ex) {
      if (ex instanceof StorageContainerException &&
          ((StorageContainerException) ex).getResult() ==
              CONTAINER_ALREADY_EXISTS) {
        throw ex;
      }
      //delete all the temporary data in case of any exception.
      try {
        FileUtils.deleteDirectory(new File(containerData.getMetadataPath()));
        FileUtils.deleteDirectory(new File(containerData.getChunksPath()));
        FileUtils.deleteDirectory(
            new File(getContainerData().getContainerPath()));
      } catch (Exception deleteex) {
        LOG.error(
            "Can not cleanup destination directories after a container import"
                + " error (cid" +
                containerData.getContainerID() + ")", deleteex);
      }
      throw ex;
    } finally {
      writeUnlock();
    }
  }

  @Override
  public void exportContainerData(OutputStream destination,
      ContainerPacker<KeyValueContainerData> packer) throws IOException {
    writeLock();
    try {
      // Closed/ Quasi closed containers are considered for replication by
      // replication manager if they are under-replicated.
      ContainerProtos.ContainerDataProto.State state =
          getContainerData().getState();
      if (!(state == ContainerProtos.ContainerDataProto.State.CLOSED ||
          state == ContainerDataProto.State.QUASI_CLOSED)) {
        throw new IllegalStateException(
            "Only (quasi)closed containers can be exported, but " +
                "ContainerId=" + getContainerData().getContainerID() +
                " is in state " + state);
      }

      try {
        compactDB();
        // Close DB (and remove from cache) to avoid concurrent modification
        // while packing it.
        BlockUtils.removeDB(containerData, config);
      } finally {
        readLock();
        writeUnlock();
      }

      packer.pack(this, destination);
    } finally {
      if (lock.isWriteLockedByCurrentThread()) {
        writeUnlock();
      } else {
        readUnlock();
      }
    }
  }

  /**
   * Acquire read lock.
   */
  @Override
  public void readLock() {
    this.lock.readLock().lock();

  }

  /**
   * Release read lock.
   */
  @Override
  public void readUnlock() {
    this.lock.readLock().unlock();
  }

  /**
   * Check if the current thread holds read lock.
   */
  @Override
  public boolean hasReadLock() {
    return this.lock.readLock().tryLock();
  }

  /**
   * Acquire write lock.
   */
  @Override
  public void writeLock() {
    // TODO: The lock for KeyValueContainer object should not be exposed
    // publicly.
    this.lock.writeLock().lock();
  }

  /**
   * Release write lock.
   */
  @Override
  public void writeUnlock() {
    this.lock.writeLock().unlock();

  }

  /**
   * Check if the current thread holds write lock.
   */
  @Override
  public boolean hasWriteLock() {
    return this.lock.writeLock().isHeldByCurrentThread();
  }

  /**
   * Acquire read lock, unless interrupted while waiting.
   * @throws InterruptedException
   */
  @Override
  public void readLockInterruptibly() throws InterruptedException {
    this.lock.readLock().lockInterruptibly();
  }

  /**
   * Acquire write lock, unless interrupted while waiting.
   * @throws InterruptedException
   */
  @Override
  public void writeLockInterruptibly() throws InterruptedException {
    this.lock.writeLock().lockInterruptibly();

  }

  /**
   * Returns containerFile.
   * @return .container File name
   */
  @Override
  public File getContainerFile() {
    return getContainerFile(containerData.getMetadataPath(),
            containerData.getContainerID());
  }

  static File getContainerFile(String metadataPath, long containerId) {
    return new File(metadataPath,
        containerId + OzoneConsts.CONTAINER_EXTENSION);
  }

  @Override
  public void updateBlockCommitSequenceId(long blockCommitSequenceId) {
    containerData.updateBlockCommitSequenceId(blockCommitSequenceId);
  }

  @Override
  public long getBlockCommitSequenceId() {
    return containerData.getBlockCommitSequenceId();
  }


  /**
   * Returns KeyValueContainerReport for the KeyValueContainer.
   */
  @Override
  public ContainerReplicaProto getContainerReport()
      throws StorageContainerException {
    ContainerReplicaProto.Builder ciBuilder =
        ContainerReplicaProto.newBuilder();
    ciBuilder.setContainerID(containerData.getContainerID())
        .setReadCount(containerData.getReadCount())
        .setWriteCount(containerData.getWriteCount())
        .setReadBytes(containerData.getReadBytes())
        .setWriteBytes(containerData.getWriteBytes())
        .setKeyCount(containerData.getKeyCount())
        .setUsed(containerData.getBytesUsed())
        .setState(getHddsState())
        .setDeleteTransactionId(containerData.getDeleteTransactionId())
        .setBlockCommitSequenceId(containerData.getBlockCommitSequenceId())
        .setOriginNodeId(containerData.getOriginNodeId());
    return ciBuilder.build();
  }

  /**
   * Returns LifeCycle State of the container.
   * @return LifeCycle State of the container in HddsProtos format
   * @throws StorageContainerException
   */
  private ContainerReplicaProto.State getHddsState()
      throws StorageContainerException {
    ContainerReplicaProto.State state;
    switch (containerData.getState()) {
    case OPEN:
      state = ContainerReplicaProto.State.OPEN;
      break;
    case CLOSING:
      state = ContainerReplicaProto.State.CLOSING;
      break;
    case QUASI_CLOSED:
      state = ContainerReplicaProto.State.QUASI_CLOSED;
      break;
    case CLOSED:
      state = ContainerReplicaProto.State.CLOSED;
      break;
    case UNHEALTHY:
      state = ContainerReplicaProto.State.UNHEALTHY;
      break;
    case DELETED:
      state = ContainerReplicaProto.State.DELETED;
      break;
    default:
      throw new StorageContainerException("Invalid Container state found: " +
          containerData.getContainerID(), INVALID_CONTAINER_STATE);
    }
    return state;
  }

  /**
   * Returns container DB file.
   * @return
   */
  public File getContainerDBFile() {
    return new File(containerData.getMetadataPath(), containerData
        .getContainerID() + OzoneConsts.DN_CONTAINER_DB);
  }

  @Override
  public boolean scanMetaData() {
    long containerId = containerData.getContainerID();
    KeyValueContainerCheck checker =
        new KeyValueContainerCheck(containerData.getMetadataPath(), config,
            containerId);
    return checker.fastCheck();
  }

  @Override
  public boolean shouldScanData() {
    return containerData.getState() == ContainerDataProto.State.CLOSED
        || containerData.getState() == ContainerDataProto.State.QUASI_CLOSED;
  }

  @Override
  public boolean scanData(DataTransferThrottler throttler, Canceler canceler) {
    if (!shouldScanData()) {
      throw new IllegalStateException("The checksum verification can not be" +
          " done for container in state "
          + containerData.getState());
    }

    long containerId = containerData.getContainerID();
    KeyValueContainerCheck checker =
        new KeyValueContainerCheck(containerData.getMetadataPath(), config,
            containerId);

    return checker.fullCheck(throttler, canceler);
  }

  private enum ContainerCheckLevel {
    NO_CHECK, FAST_CHECK, FULL_CHECK
  }

  /**
   * Creates a temporary file.
   * @param file
   * @return
   * @throws IOException
   */
  private File createTempFile(File file) throws IOException{
    return File.createTempFile("tmp_" + System.currentTimeMillis() + "_",
        file.getName(), file.getParentFile());
  }

}
