/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.io.hfile;

import java.io.DataInput;
import java.io.IOException;

import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.nio.ByteBuff;
import org.apache.hadoop.hbase.util.BloomFilter;
import org.apache.hadoop.hbase.util.BloomFilterUtil;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Hash;

/**
 * A Bloom filter implementation built on top of 
 * {@link org.apache.hadoop.hbase.util.BloomFilterChunk}, encapsulating
 * a set of fixed-size Bloom filters written out at the time of
 * {@link org.apache.hadoop.hbase.io.hfile.HFile} generation into the data
 * block stream, and loaded on demand at query time. This class only provides
 * reading capabilities.
 */
@InterfaceAudience.Private
public class CompoundBloomFilter extends CompoundBloomFilterBase
    implements BloomFilter {

  /** Used to load chunks on demand */
  private HFile.Reader reader;

  private HFileBlockIndex.BlockIndexReader index;

  private int hashCount;
  private Hash hash;

  private long[] numQueriesPerChunk;
  private long[] numPositivesPerChunk;

  /**
   * De-serialization for compound Bloom filter metadata. Must be consistent
   * with what {@link CompoundBloomFilterWriter} does.
   *
   * @param meta serialized Bloom filter metadata without any magic blocks
   * @throws IOException
   */
  public CompoundBloomFilter(DataInput meta, HFile.Reader reader)
      throws IOException {
    this.reader = reader;

    totalByteSize = meta.readLong();
    hashCount = meta.readInt();
    hashType = meta.readInt();
    totalKeyCount = meta.readLong();
    totalMaxKeys = meta.readLong();
    numChunks = meta.readInt();
    byte[] comparatorClassName = Bytes.readByteArray(meta);
    // The writer would have return 0 as the vint length for the case of 
    // Bytes.BYTES_RAWCOMPARATOR.  In such cases do not initialize comparator, it can be
    // null
    if (comparatorClassName.length != 0) {
      comparator = FixedFileTrailer.createComparator(Bytes.toString(comparatorClassName));
    }

    hash = Hash.getInstance(hashType);
    if (hash == null) {
      throw new IllegalArgumentException("Invalid hash type: " + hashType);
    }
    // We will pass null for ROW block
    if(comparator == null) {
      index = new HFileBlockIndex.ByteArrayKeyBlockIndexReader(1);
    } else {
      index = new HFileBlockIndex.CellBasedKeyBlockIndexReader(comparator, 1);
    }
    index.readRootIndex(meta, numChunks);
  }

  @Override
  public boolean contains(byte[] key, int keyOffset, int keyLength, ByteBuff bloom) {
    // We try to store the result in this variable so we can update stats for
    // testing, but when an error happens, we log a message and return.

    int block = index.rootBlockContainingKey(key, keyOffset,
        keyLength);
    return checkContains(key, keyOffset, keyLength, block);
  }

  private boolean checkContains(byte[] key, int keyOffset, int keyLength, int block) {
    boolean result;
    if (block < 0) {
      result = false; // This key is not in the file.
    } else {
      HFileBlock bloomBlock;
      try {
        // We cache the block and use a positional read.
        bloomBlock = reader.readBlock(index.getRootBlockOffset(block),
            index.getRootBlockDataSize(block), true, true, false, true,
            BlockType.BLOOM_CHUNK, null);
      } catch (IOException ex) {
        // The Bloom filter is broken, turn it off.
        throw new IllegalArgumentException(
            "Failed to load Bloom block for key "
                + Bytes.toStringBinary(key, keyOffset, keyLength), ex);
      }
      try {
        ByteBuff bloomBuf = bloomBlock.getBufferReadOnly();
        result =
            BloomFilterUtil.contains(key, keyOffset, keyLength, bloomBuf, bloomBlock.headerSize(),
              bloomBlock.getUncompressedSizeWithoutHeader(), hash, hashCount);
      } finally {
        // After the use return back the block if it was served from a cache.
        reader.returnBlock(bloomBlock);
      }
    }

    if (numQueriesPerChunk != null && block >= 0) {
      // Update statistics. Only used in unit tests.
      ++numQueriesPerChunk[block];
      if (result)
        ++numPositivesPerChunk[block];
    }

    return result;
  }

  @Override
  public boolean contains(Cell keyCell, ByteBuff bloom) {
    // We try to store the result in this variable so we can update stats for
    // testing, but when an error happens, we log a message and return.
    int block = index.rootBlockContainingKey(keyCell);
    // This copy will be needed. Because blooms work on the key part only.
    // Atleast we now avoid multiple copies until it comes here. If we want to make this to work
    // with BBs then the Hash.java APIs should also be changed to work with BBs.
    if (keyCell instanceof KeyValue) {
      // TODO : directly use Cell here
      return checkContains(((KeyValue) keyCell).getBuffer(), ((KeyValue) keyCell).getKeyOffset(),
        ((KeyValue) keyCell).getKeyLength(), block);
    }
    // TODO : Avoid this copy in read path also
    byte[] key = CellUtil.getCellKeySerializedAsKeyValueKey(keyCell);
    return checkContains(key, 0, key.length, block);
  }

  public boolean supportsAutoLoading() {
    return true;
  }

  public int getNumChunks() {
    return numChunks;
  }

  public void enableTestingStats() {
    numQueriesPerChunk = new long[numChunks];
    numPositivesPerChunk = new long[numChunks];
  }

  public String formatTestingStats() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < numChunks; ++i) {
      sb.append("chunk #");
      sb.append(i);
      sb.append(": queries=");
      sb.append(numQueriesPerChunk[i]);
      sb.append(", positives=");
      sb.append(numPositivesPerChunk[i]);
      sb.append(", positiveRatio=");
      sb.append(numPositivesPerChunk[i] * 1.0 / numQueriesPerChunk[i]);
      sb.append(";\n");
    }
    return sb.toString();
  }

  public long getNumQueriesForTesting(int chunk) {
    return numQueriesPerChunk[chunk];
  }

  public long getNumPositivesForTesting(int chunk) {
    return numPositivesPerChunk[chunk];
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(BloomFilterUtil.formatStats(this));
    sb.append(BloomFilterUtil.STATS_RECORD_SEP + 
        "Number of chunks: " + numChunks);
    sb.append(BloomFilterUtil.STATS_RECORD_SEP + 
        ((comparator != null) ? "Comparator: "
        + comparator.getClass().getSimpleName() : "Comparator: "
        + Bytes.BYTES_RAWCOMPARATOR.getClass().getSimpleName()));
    return sb.toString();
  }

}
