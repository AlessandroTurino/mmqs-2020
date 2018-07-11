package org.cache2k.core;

/*
 * #%L
 * cache2k implementation
 * %%
 * Copyright (C) 2000 - 2018 headissue GmbH, Munich
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import org.cache2k.core.concurrency.Job;

/**
 * Basic eviction functionality.
 *
 * @author Jens Wilke
 */
@SuppressWarnings({"WeakerAccess", "SynchronizationOnLocalVariableOrMethodParameter"})
public abstract class AbstractEviction implements Eviction, EvictionMetrics {

  public static final int MINIMAL_CHUNK_SIZE = 4;
  public static final int MAXIMAL_CHUNK_SIZE = 64;
  public static final long MINIMUM_CAPACITY_FOR_CHUNKING = 1000;

  protected final long maxSize;
  protected final long correctedMaxSize;
  protected final HeapCache heapCache;
  private final Object lock = new Object();
  private long newEntryCounter;
  private long removedCnt;
  private long expiredRemovedCnt;
  private long virginRemovedCnt;
  private long evictedCount;
  private final HeapCacheListener listener;
  private final boolean noListenerCall;
  private Entry[] evictChunkReuse;
  private int chunkSize;
  private int evictionRunningCount = 0;

  public AbstractEviction(final HeapCache _heapCache, final HeapCacheListener _listener, final long _maxSize) {
    heapCache = _heapCache;
    listener = _listener;
    maxSize = _maxSize;
    if (_maxSize < MINIMUM_CAPACITY_FOR_CHUNKING) {
      chunkSize = 1;
    } else {
      chunkSize = MINIMAL_CHUNK_SIZE + Runtime.getRuntime().availableProcessors() - 1;
      chunkSize = Math.min(MAXIMAL_CHUNK_SIZE, chunkSize);
    }
    noListenerCall = _listener instanceof HeapCacheListener.NoOperation;
    /**
     * Avoid integer overflow when calculating with the max size.
     */
    if (maxSize == Long.MAX_VALUE) {
      correctedMaxSize = Long.MAX_VALUE >> 1;
    } else {
      correctedMaxSize = maxSize;
    }
  }

  /** Safe GC overhead by reusing the chunk array. */
  Entry[] reuseChunkArray() {
    Entry[] ea = evictChunkReuse;
    if (ea != null) {
      evictChunkReuse = null;
    } else {
      ea = new Entry[chunkSize];
    }
    return ea;
  }

  private void removeEventually(final Entry e) {
    if (!e.isRemovedFromReplacementList()) {
      removeFromReplacementList(e);
      long nrt = e.getNextRefreshTime();
      if (nrt == (Entry.GONE + Entry.EXPIRED)) {
        expiredRemovedCnt++;
      } else if (nrt == (Entry.GONE + Entry.VIRGIN)) {
        virginRemovedCnt++;
      } else {
        removedCnt++;
      }
    }
  }

  @Override
  public boolean submitWithoutEviction(final Entry e) {
    synchronized (lock) {
      if (e.isNotYetInsertedInReplacementList()) {
        insertIntoReplacementList(e);
        newEntryCounter++;
      } else {
        removeEventually(e);
      }
      return evictionNeeded();
    }
  }

  /**
   * Do we need to trigger an eviction? For chunks sizes more than 1 the eviction
   * kicks later.
   */
  boolean evictionNeeded() {
    return getSize() > (correctedMaxSize + evictionRunningCount + chunkSize / 2);
  }

  @Override
  public void evictEventually() {
    Entry[] _chunk;
    synchronized (lock) {
      _chunk = fillEvictionChunk();
    }
    evictChunk(_chunk);
  }

  @Override
  public void evictEventually(final int hc) {
    evictEventually();
  }

  private Entry[] fillEvictionChunk() {
    if (!evictionNeeded()) {
      return null;
    }
    final Entry[] _chunk = reuseChunkArray();
    return refillChunk(_chunk);
  }

  private Entry[] refillChunk(Entry[] _chunk) {
    if (_chunk == null) {
      _chunk = new Entry[chunkSize];
    }
    evictionRunningCount += _chunk.length;
    for (int i = 0; i < _chunk.length; i++) {
      _chunk[i] = findEvictionCandidate(null);
    }
    return _chunk;
  }

  private void evictChunk(Entry[] _chunk) {
    if (_chunk == null) { return; }
    removeFromHash(_chunk);
    synchronized (lock) {
      removeAllFromReplacementListOnEvict(_chunk);
      evictionRunningCount -= _chunk.length;
      evictChunkReuse = _chunk;
    }
  }

  private void removeFromHash(final Entry[] _chunk) {
    removeFromHashWithoutListener(_chunk);
  }

  private void removeFromHashWithoutListener(final Entry[] _chunk) {
    for (int i = 0; i < _chunk.length; i++) {
      Entry e = _chunk[i];
      synchronized (e) {
        if (e.isGone() || e.isProcessing()) {
          _chunk[i] = null; continue;
        }
        heapCache.removeEntryForEviction(e);
      }
    }
  }

  private void removeAllFromReplacementListOnEvict(final Entry[] _chunk) {
    for (int i = 0; i < _chunk.length; i++) {
      Entry e = _chunk[i];
      if (e != null) {
        if (!e.isRemovedFromReplacementList()) {
          removeFromReplacementListOnEvict(e);
          evictedCount++;
        }
        /* we reuse the chunk array, null the array position to avoid memory leak */
        _chunk[i] = null;
      }
    }
  }

  @Override
  public long getNewEntryCount() {
    return newEntryCounter;
  }

  @Override
  public long getRemovedCount() {
    return removedCnt;
  }

  @Override
  public long getVirginRemovedCount() {
    return virginRemovedCnt;
  }

  @Override
  public long getExpiredRemovedCount() {
    return expiredRemovedCnt;
  }

  @Override
  public long getEvictedCount() {
    return evictedCount;
  }

  @Override
  public long getMaxSize() {
    return maxSize;
  }

  @Override
  public int getEvictionRunningCount() {
    return evictionRunningCount;
  }

  @Override
  public EvictionMetrics getMetrics() {
    return this;
  }

  @Override
  public void start() { }

  @Override
  public void stop() { }

  @Override
  public boolean drain() {
    return false;
  }

  @Override
  public void close() { }

  @Override
  public <T> T runLocked(final Job<T> j) {
    synchronized (lock) {
      return j.call();
    }
  }

  protected void removeFromReplacementListOnEvict(Entry e) { removeFromReplacementList(e); }

  protected abstract Entry findEvictionCandidate(Entry e);
  protected abstract void removeFromReplacementList(Entry e);
  protected abstract void insertIntoReplacementList(Entry e);

  @Override
  public String getExtraStatistics() {
    return
      "impl=" + this.getClass().getSimpleName() +
      ", chunkSize=" + chunkSize;
  }

}
