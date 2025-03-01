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

package org.apache.hadoop.hdds.scm.storage;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.google.common.base.Preconditions;
import org.apache.hadoop.fs.ByteBufferReadable;
import org.apache.hadoop.fs.CanUnbuffer;
import org.apache.hadoop.fs.Seekable;
import org.apache.hadoop.hdds.client.BlockID;
import org.apache.hadoop.hdds.client.ReplicationConfig;
import org.apache.hadoop.hdds.client.StandaloneReplicationConfig;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ChunkInfo;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.DatanodeBlockID;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.GetBlockResponseProto;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.scm.XceiverClientFactory;
import org.apache.hadoop.hdds.scm.XceiverClientSpi;
import org.apache.hadoop.hdds.scm.client.HddsClientUtils;
import org.apache.hadoop.hdds.scm.container.common.helpers.StorageContainerException;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.hdds.security.exception.SCMSecurityException;
import org.apache.hadoop.hdds.security.token.OzoneBlockTokenIdentifier;
import org.apache.hadoop.io.retry.RetryPolicy;
import org.apache.hadoop.security.token.Token;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link InputStream} called from KeyInputStream to read a block from the
 * container.
 * This class encapsulates all state management for iterating
 * through the sequence of chunks through {@link ChunkInputStream}.
 */
public class BlockInputStream extends InputStream
    implements Seekable, CanUnbuffer, ByteBufferReadable {

  private static final Logger LOG =
      LoggerFactory.getLogger(BlockInputStream.class);

  private static final int EOF = -1;

  private final BlockID blockID;
  private final long length;
  private Pipeline pipeline;
  private final Token<OzoneBlockTokenIdentifier> token;
  private final boolean verifyChecksum;
  private XceiverClientFactory xceiverClientFactory;
  private XceiverClientSpi xceiverClient;
  private boolean initialized = false;
  // TODO: do we need to change retrypolicy based on exception.
  private final RetryPolicy retryPolicy =
      HddsClientUtils.createRetryPolicy(3, TimeUnit.SECONDS.toMillis(1));
  private int retries;

  // List of ChunkInputStreams, one for each chunk in the block
  private List<ChunkInputStream> chunkStreams;

  // chunkOffsets[i] stores the index of the first data byte in
  // chunkStream i w.r.t the block data.
  // Let’s say we have chunk size as 40 bytes. And let's say the parent
  // block stores data from index 200 and has length 400.
  // The first 40 bytes of this block will be stored in chunk[0], next 40 in
  // chunk[1] and so on. But since the chunkOffsets are w.r.t the block only
  // and not the key, the values in chunkOffsets will be [0, 40, 80,....].
  private long[] chunkOffsets = null;

  // Index of the chunkStream corresponding to the current position of the
  // BlockInputStream i.e offset of the data to be read next from this block
  private int chunkIndex;

  // Position of the BlockInputStream is maintained by this variable till
  // the stream is initialized. This position is w.r.t to the block only and
  // not the key.
  // For the above example, if we seek to position 240 before the stream is
  // initialized, then value of blockPosition will be set to 40.
  // Once, the stream is initialized, the position of the stream
  // will be determined by the current chunkStream and its position.
  private long blockPosition = 0;

  // Tracks the chunkIndex corresponding to the last blockPosition so that it
  // can be reset if a new position is seeked.
  private int chunkIndexOfPrevPosition;

  private final Function<BlockID, Pipeline> refreshPipelineFunction;

  public BlockInputStream(BlockID blockId, long blockLen, Pipeline pipeline,
      Token<OzoneBlockTokenIdentifier> token, boolean verifyChecksum,
      XceiverClientFactory xceiverClientFactory,
      Function<BlockID, Pipeline> refreshPipelineFunction) {
    this.blockID = blockId;
    this.length = blockLen;
    this.pipeline = pipeline;
    this.token = token;
    this.verifyChecksum = verifyChecksum;
    this.xceiverClientFactory = xceiverClientFactory;
    this.refreshPipelineFunction = refreshPipelineFunction;
  }

  public BlockInputStream(BlockID blockId, long blockLen, Pipeline pipeline,
                          Token<OzoneBlockTokenIdentifier> token,
                          boolean verifyChecksum,
                          XceiverClientFactory xceiverClientFactory
  ) {
    this(blockId, blockLen, pipeline, token, verifyChecksum,
        xceiverClientFactory, null);
  }
  /**
   * Initialize the BlockInputStream. Get the BlockData (list of chunks) from
   * the Container and create the ChunkInputStreams for each Chunk in the Block.
   */
  public synchronized void initialize() throws IOException {

    // Pre-check that the stream has not been intialized already
    if (initialized) {
      return;
    }

    List<ChunkInfo> chunks = null;
    IOException catchEx = null;
    do {
      try {
        // If refresh returns new pipeline, retry with it.
        // If we get IOException due to connectivity issue,
        // retry according to retry policy.
        chunks = getChunkInfos();
        break;
      } catch(SCMSecurityException ex) {
        throw ex;
      } catch (StorageContainerException ex) {
        refreshPipeline(ex);
        catchEx = ex;
      } catch (IOException ex) {
        LOG.debug("Retry to get chunk info fail", ex);
        catchEx = ex;
      }
    } while (shouldRetryRead(catchEx));

    if (chunks == null) {
      throw catchEx;
    } else {
      // Reset retry count if we get chunks successfully.
      retries = 0;
    }

    if (!chunks.isEmpty()) {
      // For each chunk in the block, create a ChunkInputStream and compute
      // its chunkOffset
      this.chunkOffsets = new long[chunks.size()];
      long tempOffset = 0;

      this.chunkStreams = new ArrayList<>(chunks.size());
      for (int i = 0; i < chunks.size(); i++) {
        addStream(chunks.get(i));
        chunkOffsets[i] = tempOffset;
        tempOffset += chunks.get(i).getLen();
      }

      initialized = true;
      this.chunkIndex = 0;

      if (blockPosition > 0) {
        // Stream was seeked to blockPosition before initialization. Seek to the
        // blockPosition now.
        seek(blockPosition);
      }
    }
  }

  private void refreshPipeline(IOException cause) throws IOException {
    LOG.info("Unable to read information for block {} from pipeline {}: {}",
        blockID, pipeline.getId(), cause.getMessage());
    if (refreshPipelineFunction != null) {
      LOG.debug("Re-fetching pipeline for block {}", blockID);
      Pipeline newPipeline = refreshPipelineFunction.apply(blockID);
      if (newPipeline == null || newPipeline.sameDatanodes(pipeline)) {
        LOG.warn("No new pipeline for block {}", blockID);
        throw cause;
      } else {
        LOG.debug("New pipeline got for block {}", blockID);
        this.pipeline = newPipeline;
      }
    } else {
      throw cause;
    }
  }

  /**
   * Send RPC call to get the block info from the container.
   * @return List of chunks in this block.
   */
  protected List<ChunkInfo> getChunkInfos() throws IOException {
    // irrespective of the container state, we will always read via Standalone
    // protocol.
    if (pipeline.getType() != HddsProtos.ReplicationType.STAND_ALONE) {
      pipeline = Pipeline.newBuilder(pipeline)
          .setReplicationConfig(new StandaloneReplicationConfig(
              ReplicationConfig
                  .getLegacyFactor(pipeline.getReplicationConfig())))
          .build();
    }
    acquireClient();
    boolean success = false;
    List<ChunkInfo> chunks;
    try {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Initializing BlockInputStream for get key to access {}",
            blockID.getContainerID());
      }

      DatanodeBlockID datanodeBlockID = blockID
          .getDatanodeBlockIDProtobuf();
      GetBlockResponseProto response = ContainerProtocolCalls
          .getBlock(xceiverClient, datanodeBlockID, token);

      chunks = response.getBlockData().getChunksList();
      success = true;
    } finally {
      if (!success) {
        xceiverClientFactory.releaseClientForReadData(xceiverClient, false);
      }
    }

    return chunks;
  }

  protected void acquireClient() throws IOException {
    xceiverClient = xceiverClientFactory.acquireClientForReadData(pipeline);
  }

  /**
   * Append another ChunkInputStream to the end of the list. Note that the
   * ChunkInputStream is only created here. The chunk will be read from the
   * Datanode only when a read operation is performed on for that chunk.
   */
  protected synchronized void addStream(ChunkInfo chunkInfo) {
    chunkStreams.add(createChunkInputStream(chunkInfo));
  }

  protected ChunkInputStream createChunkInputStream(ChunkInfo chunkInfo) {
    return new ChunkInputStream(chunkInfo, blockID,
        xceiverClientFactory, () -> pipeline, verifyChecksum, token);
  }

  public synchronized long getRemaining() {
    return length - getPos();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public synchronized int read() throws IOException {
    byte[] buf = new byte[1];
    if (read(buf, 0, 1) == EOF) {
      return EOF;
    }
    return Byte.toUnsignedInt(buf[0]);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public synchronized int read(byte[] b, int off, int len) throws IOException {
    ByteReaderStrategy strategy = new ByteArrayReader(b, off, len);
    if (len == 0) {
      return 0;
    }
    return readWithStrategy(strategy);
  }

  @Override
  public synchronized int read(ByteBuffer byteBuffer) throws IOException {
    ByteReaderStrategy strategy = new ByteBufferReader(byteBuffer);
    int len = strategy.getTargetLength();
    if (len == 0) {
      return 0;
    }
    return readWithStrategy(strategy);
  }

  synchronized int readWithStrategy(ByteReaderStrategy strategy) throws
      IOException {
    Preconditions.checkArgument(strategy != null);
    if (!initialized) {
      initialize();
    }

    checkOpen();
    int totalReadLen = 0;
    int len = strategy.getTargetLength();
    while (len > 0) {
      // if we are at the last chunk and have read the entire chunk, return
      if (chunkStreams.size() == 0 ||
          (chunkStreams.size() - 1 <= chunkIndex &&
              chunkStreams.get(chunkIndex)
                  .getRemaining() == 0)) {
        return totalReadLen == 0 ? EOF : totalReadLen;
      }

      // Get the current chunkStream and read data from it
      ChunkInputStream current = chunkStreams.get(chunkIndex);
      int numBytesToRead = Math.min(len, (int)current.getRemaining());
      int numBytesRead;
      try {
        numBytesRead = strategy.readFromBlock(current, numBytesToRead);
        retries = 0; // reset retries after successful read
      } catch (StorageContainerException e) {
        if (shouldRetryRead(e)) {
          handleReadError(e);
          continue;
        } else {
          throw e;
        }
      } catch(SCMSecurityException ex) {
        throw ex;
      } catch(IOException ex) {
        // We got a IOException which might be due
        // to DN down or connectivity issue.
        if (shouldRetryRead(ex)) {
          current.releaseClient();
          continue;
        } else {
          throw ex;
        }
      }

      if (numBytesRead != numBytesToRead) {
        // This implies that there is either data loss or corruption in the
        // chunk entries. Even EOF in the current stream would be covered in
        // this case.
        throw new IOException(String.format(
            "Inconsistent read for chunkName=%s length=%d numBytesToRead= %d " +
                "numBytesRead=%d", current.getChunkName(), current.getLength(),
            numBytesToRead, numBytesRead));
      }
      totalReadLen += numBytesRead;
      len -= numBytesRead;
      if (current.getRemaining() <= 0 &&
          ((chunkIndex + 1) < chunkStreams.size())) {
        chunkIndex += 1;
      }
    }
    return totalReadLen;
  }

  /**
   * Seeks the BlockInputStream to the specified position. If the stream is
   * not initialized, save the seeked position via blockPosition. Otherwise,
   * update the position in 2 steps:
   *    1. Updating the chunkIndex to the chunkStream corresponding to the
   *    seeked position.
   *    2. Seek the corresponding chunkStream to the adjusted position.
   *
   * Let’s say we have chunk size as 40 bytes. And let's say the parent block
   * stores data from index 200 and has length 400. If the key was seeked to
   * position 90, then this block will be seeked to position 90.
   * When seek(90) is called on this blockStream, then
   *    1. chunkIndex will be set to 2 (as indices 80 - 120 reside in chunk[2]).
   *    2. chunkStream[2] will be seeked to position 10
   *       (= 90 - chunkOffset[2] (= 80)).
   */
  @Override
  public synchronized void seek(long pos) throws IOException {
    if (!initialized) {
      // Stream has not been initialized yet. Save the position so that it
      // can be seeked when the stream is initialized.
      blockPosition = pos;
      return;
    }

    checkOpen();
    if (pos < 0 || pos >= length) {
      if (pos == 0) {
        // It is possible for length and pos to be zero in which case
        // seek should return instead of throwing exception
        return;
      }
      throw new EOFException(
          "EOF encountered at pos: " + pos + " for block: " + blockID);
    }

    if (chunkIndex >= chunkStreams.size()) {
      chunkIndex = Arrays.binarySearch(chunkOffsets, pos);
    } else if (pos < chunkOffsets[chunkIndex]) {
      chunkIndex =
          Arrays.binarySearch(chunkOffsets, 0, chunkIndex, pos);
    } else if (pos >= chunkOffsets[chunkIndex] + chunkStreams
        .get(chunkIndex).getLength()) {
      chunkIndex = Arrays.binarySearch(chunkOffsets,
          chunkIndex + 1, chunkStreams.size(), pos);
    }
    if (chunkIndex < 0) {
      // Binary search returns -insertionPoint - 1  if element is not present
      // in the array. insertionPoint is the point at which element would be
      // inserted in the sorted array. We need to adjust the chunkIndex
      // accordingly so that chunkIndex = insertionPoint - 1
      chunkIndex = -chunkIndex - 2;
    }

    // Reset the previous chunkStream's position
    chunkStreams.get(chunkIndexOfPrevPosition).resetPosition();

    // Reset all the chunkStreams above the chunkIndex. We do this to reset
    // any previous reads which might have updated the chunkPosition.
    for (int index =  chunkIndex + 1; index < chunkStreams.size(); index++) {
      chunkStreams.get(index).seek(0);
    }
    // seek to the proper offset in the ChunkInputStream
    chunkStreams.get(chunkIndex).seek(pos - chunkOffsets[chunkIndex]);
    chunkIndexOfPrevPosition = chunkIndex;
  }

  @Override
  public synchronized long getPos() {
    if (length == 0) {
      return 0;
    }

    if (!initialized) {
      // The stream is not initialized yet. Return the blockPosition
      return blockPosition;
    } else {
      return chunkOffsets[chunkIndex] + chunkStreams.get(chunkIndex).getPos();
    }
  }

  @Override
  public boolean seekToNewSource(long targetPos) throws IOException {
    return false;
  }

  @Override
  public synchronized void close() {
    releaseClient();
    xceiverClientFactory = null;

    final List<ChunkInputStream> inputStreams = this.chunkStreams;
    if (inputStreams != null) {
      for (ChunkInputStream is : inputStreams) {
        is.close();
      }
    }
  }

  private void releaseClient() {
    if (xceiverClientFactory != null && xceiverClient != null) {
      xceiverClientFactory.releaseClient(xceiverClient, false);
      xceiverClient = null;
    }
  }

  public synchronized void resetPosition() {
    this.blockPosition = 0;
  }

  /**
   * Checks if the stream is open.  If not, throw an exception.
   *
   * @throws IOException if stream is closed
   */
  protected synchronized void checkOpen() throws IOException {
    if (xceiverClientFactory == null) {
      throw new IOException("BlockInputStream has been closed.");
    }
  }

  public BlockID getBlockID() {
    return blockID;
  }

  public long getLength() {
    return length;
  }

  @VisibleForTesting
  synchronized int getChunkIndex() {
    return chunkIndex;
  }

  @VisibleForTesting
  synchronized long getBlockPosition() {
    return blockPosition;
  }

  @Override
  public void unbuffer() {
    storePosition();
    releaseClient();

    final List<ChunkInputStream> inputStreams = this.chunkStreams;
    if (inputStreams != null) {
      for (ChunkInputStream is : inputStreams) {
        is.unbuffer();
      }
    }
  }

  private synchronized void storePosition() {
    blockPosition = getPos();
  }

  private boolean shouldRetryRead(IOException cause) throws IOException {
    RetryPolicy.RetryAction retryAction;
    try {
      retryAction = retryPolicy.shouldRetry(cause, ++retries, 0, true);
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException(e);
    }
    return retryAction.action == RetryPolicy.RetryAction.RetryDecision.RETRY;
  }

  private void handleReadError(IOException cause) throws IOException {
    releaseClient();
    final List<ChunkInputStream> inputStreams = this.chunkStreams;
    if (inputStreams != null) {
      for (ChunkInputStream is : inputStreams) {
        is.releaseClient();
      }
    }

    refreshPipeline(cause);
  }

  @VisibleForTesting
  public synchronized List<ChunkInputStream> getChunkStreams() {
    return chunkStreams;
  }

  @VisibleForTesting
  public static Logger getLog() {
    return LOG;
  }
}
