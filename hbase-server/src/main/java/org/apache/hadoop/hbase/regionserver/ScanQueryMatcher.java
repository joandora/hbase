/**
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

package org.apache.hadoop.hbase.regionserver;

import java.io.IOException;
import java.util.Arrays;
import java.util.NavigableSet;

import org.apache.hadoop.hbase.KeyValue.Type;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellComparator;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeepDeletedCells;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.KeyValueUtil;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.Filter.ReturnCode;
import org.apache.hadoop.hbase.io.TimeRange;
import org.apache.hadoop.hbase.regionserver.DeleteTracker.DeleteResult;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;

import com.google.common.base.Preconditions;

/**
 * A query matcher that is specifically designed for the scan case.
 */
@InterfaceAudience.Private
public class ScanQueryMatcher {
  // Optimization so we can skip lots of compares when we decide to skip
  // to the next row.
  private boolean stickyNextRow;
  private final byte[] stopRow;

  private final TimeRange tr;

  private final Filter filter;

  /** Keeps track of deletes */
  private final DeleteTracker deletes;

  /*
   * The following three booleans define how we deal with deletes.
   * There are three different aspects:
   * 1. Whether to keep delete markers. This is used in compactions.
   *    Minor compactions always keep delete markers.
   * 2. Whether to keep deleted rows. This is also used in compactions,
   *    if the store is set to keep deleted rows. This implies keeping
   *    the delete markers as well.
   *    In this case deleted rows are subject to the normal max version
   *    and TTL/min version rules just like "normal" rows.
   * 3. Whether a scan can do time travel queries even before deleted
   *    marker to reach deleted rows.
   */
  /** whether to retain delete markers */
  private boolean retainDeletesInOutput;

  /** whether to return deleted rows */
  private final KeepDeletedCells keepDeletedCells;
  /** whether time range queries can see rows "behind" a delete */
  private final boolean seePastDeleteMarkers;


  /** Keeps track of columns and versions */
  private final ColumnTracker columns;

  /** Key to seek to in memstore and StoreFiles */
  private final Cell startKey;

  /** Row comparator for the region this query is for */
  private final CellComparator rowComparator;

  /* row is not private for tests */
  /** Row the query is on */
  Cell curCell;

  /**
   * Oldest put in any of the involved store files
   * Used to decide whether it is ok to delete
   * family delete marker of this store keeps
   * deleted KVs.
   */
  private final long earliestPutTs;
  private final long ttl;

  /** The oldest timestamp we are interested in, based on TTL */
  private final long oldestUnexpiredTS;
  private final long now;

  /** readPoint over which the KVs are unconditionally included */
  protected long maxReadPointToTrackVersions;

  private byte[] dropDeletesFromRow = null, dropDeletesToRow = null;

  /**
   * This variable shows whether there is an null column in the query. There
   * always exists a null column in the wildcard column query.
   * There maybe exists a null column in the explicit column query based on the
   * first column.
   * */
  private boolean hasNullColumn = true;

  private RegionCoprocessorHost regionCoprocessorHost= null;

  // By default, when hbase.hstore.time.to.purge.deletes is 0ms, a delete
  // marker is always removed during a major compaction. If set to non-zero
  // value then major compaction will try to keep a delete marker around for
  // the given number of milliseconds. We want to keep the delete markers
  // around a bit longer because old puts might appear out-of-order. For
  // example, during log replication between two clusters.
  //
  // If the delete marker has lived longer than its column-family's TTL then
  // the delete marker will be removed even if time.to.purge.deletes has not
  // passed. This is because all the Puts that this delete marker can influence
  // would have also expired. (Removing of delete markers on col family TTL will
  // not happen if min-versions is set to non-zero)
  //
  // But, if time.to.purge.deletes has not expired then a delete
  // marker will not be removed just because there are no Puts that it is
  // currently influencing. This is because Puts, that this delete can
  // influence.  may appear out of order.
  private final long timeToPurgeDeletes;

  private final boolean isUserScan;

  private final boolean isReversed;

  /**
   * True if we are doing a 'Get' Scan. Every Get is actually a one-row Scan.
   */
  private final boolean get;

  /**
   * Construct a QueryMatcher for a scan
   * @param scanInfo The store's immutable scan info
   * @param scanType Type of the scan
   * @param earliestPutTs Earliest put seen in any of the store files.
   * @param oldestUnexpiredTS the oldest timestamp we are interested in, based on TTL
   */
  public ScanQueryMatcher(Scan scan, ScanInfo scanInfo, NavigableSet<byte[]> columns,
      ScanType scanType, long readPointToUse, long earliestPutTs, long oldestUnexpiredTS,
      long now, RegionCoprocessorHost regionCoprocessorHost) throws IOException {
    TimeRange timeRange = scan.getColumnFamilyTimeRange().get(scanInfo.getFamily());
    if (timeRange == null) {
      this.tr = scan.getTimeRange();
    } else {
      this.tr = timeRange;
    }
    this.get = scan.isGetScan();
    this.rowComparator = scanInfo.getComparator();
    this.regionCoprocessorHost = regionCoprocessorHost;
    this.deletes =  instantiateDeleteTracker();
    this.stopRow = scan.getStopRow();
    this.startKey = CellUtil.createFirstDeleteFamilyCellOnRow(scan.getStartRow(),
        scanInfo.getFamily());
    this.filter = scan.getFilter();
    this.earliestPutTs = earliestPutTs;
    this.oldestUnexpiredTS = oldestUnexpiredTS;
    this.now = now;

    this.maxReadPointToTrackVersions = readPointToUse;
    this.timeToPurgeDeletes = scanInfo.getTimeToPurgeDeletes();
    this.ttl = oldestUnexpiredTS;

    /* how to deal with deletes */
    this.isUserScan = scanType == ScanType.USER_SCAN;
    // keep deleted cells: if compaction or raw scan
    this.keepDeletedCells = scan.isRaw() ? KeepDeletedCells.TRUE :
      isUserScan ? KeepDeletedCells.FALSE : scanInfo.getKeepDeletedCells();
    // retain deletes: if minor compaction or raw scanisDone
    this.retainDeletesInOutput = scanType == ScanType.COMPACT_RETAIN_DELETES || scan.isRaw();
    // seePastDeleteMarker: user initiated scans
    this.seePastDeleteMarkers =
        scanInfo.getKeepDeletedCells() != KeepDeletedCells.FALSE && isUserScan;

    int maxVersions =
        scan.isRaw() ? scan.getMaxVersions() : Math.min(scan.getMaxVersions(),
          scanInfo.getMaxVersions());

    // Single branch to deal with two types of reads (columns vs all in family)
    if (columns == null || columns.size() == 0) {
      // there is always a null column in the wildcard column query.
      hasNullColumn = true;

      // use a specialized scan for wildcard column tracker.
      this.columns = new ScanWildcardColumnTracker(
          scanInfo.getMinVersions(), maxVersions, oldestUnexpiredTS);
    } else {
      // whether there is null column in the explicit column query
      hasNullColumn = (columns.first().length == 0);

      // We can share the ExplicitColumnTracker, diff is we reset
      // between rows, not between storefiles.
      this.columns = new ExplicitColumnTracker(columns, scanInfo.getMinVersions(), maxVersions,
          oldestUnexpiredTS);
    }
    this.isReversed = scan.isReversed();
  }

  private DeleteTracker instantiateDeleteTracker() throws IOException {
    DeleteTracker tracker = new ScanDeleteTracker();
    if (regionCoprocessorHost != null) {
      tracker = regionCoprocessorHost.postInstantiateDeleteTracker(tracker);
    }
    return tracker;
  }

  /**
   * Construct a QueryMatcher for a scan that drop deletes from a limited range of rows.
   * @param scan
   * @param scanInfo The store's immutable scan info
   * @param columns
   * @param earliestPutTs Earliest put seen in any of the store files.
   * @param oldestUnexpiredTS the oldest timestamp we are interested in, based on TTL
   * @param now the current server time
   * @param dropDeletesFromRow The inclusive left bound of the range; can be EMPTY_START_ROW.
   * @param dropDeletesToRow The exclusive right bound of the range; can be EMPTY_END_ROW.
   * @param regionCoprocessorHost
   * @throws IOException
   */
  public ScanQueryMatcher(Scan scan, ScanInfo scanInfo, NavigableSet<byte[]> columns,
      long readPointToUse, long earliestPutTs, long oldestUnexpiredTS, long now,
      byte[] dropDeletesFromRow, byte[] dropDeletesToRow,
      RegionCoprocessorHost regionCoprocessorHost) throws IOException {
    this(scan, scanInfo, columns, ScanType.COMPACT_RETAIN_DELETES, readPointToUse, earliestPutTs,
        oldestUnexpiredTS, now, regionCoprocessorHost);
    Preconditions.checkArgument((dropDeletesFromRow != null) && (dropDeletesToRow != null));
    this.dropDeletesFromRow = dropDeletesFromRow;
    this.dropDeletesToRow = dropDeletesToRow;
  }

  /*
   * Constructor for tests
   */
  ScanQueryMatcher(Scan scan, ScanInfo scanInfo,
      NavigableSet<byte[]> columns, long oldestUnexpiredTS, long now) throws IOException {
    this(scan, scanInfo, columns, ScanType.USER_SCAN,
          Long.MAX_VALUE, /* max Readpoint to track versions */
        HConstants.LATEST_TIMESTAMP, oldestUnexpiredTS, now, null);
  }

  /**
   *
   * @return  whether there is an null column in the query
   */
  public boolean hasNullColumnInQuery() {
    return hasNullColumn;
  }

  /**
   * Determines if the caller should do one of several things:
   * - seek/skip to the next row (MatchCode.SEEK_NEXT_ROW)
   * - seek/skip to the next column (MatchCode.SEEK_NEXT_COL)
   * - include the current KeyValue (MatchCode.INCLUDE)
   * - ignore the current KeyValue (MatchCode.SKIP)
   * - got to the next row (MatchCode.DONE)
   *
   * @param cell KeyValue to check
   * @return The match code instance.
   * @throws IOException in case there is an internal consistency problem
   *      caused by a data corruption.
   */
  public MatchCode match(Cell cell) throws IOException {
    if (filter != null && filter.filterAllRemaining()) {
      return MatchCode.DONE_SCAN;
    }
    if (curCell != null) {
      int ret = this.rowComparator.compareRows(curCell, cell);
      if (!this.isReversed) {
        if (ret <= -1) {
          return MatchCode.DONE;
        } else if (ret >= 1) {
          // could optimize this, if necessary?
          // Could also be called SEEK_TO_CURRENT_ROW, but this
          // should be rare/never happens.
          return MatchCode.SEEK_NEXT_ROW;
        }
      } else {
        if (ret <= -1) {
          return MatchCode.SEEK_NEXT_ROW;
        } else if (ret >= 1) {
          return MatchCode.DONE;
        }
      }
    } else {
      // Since the curCell is null it means we are already sure that we have moved over to the next row
      return MatchCode.DONE;
    }

    // optimize case.
    if (this.stickyNextRow) {
      return MatchCode.SEEK_NEXT_ROW;
    }

    if (this.columns.done()) {
      stickyNextRow = true;
      return MatchCode.SEEK_NEXT_ROW;
    }

    long timestamp = cell.getTimestamp();
    // check for early out based on timestamp alone
    if (columns.isDone(timestamp)) {
      return columns.getNextRowOrNextColumn(cell);
    }
    // check if the cell is expired by cell TTL
    if (HStore.isCellTTLExpired(cell, this.oldestUnexpiredTS, this.now)) {
      return MatchCode.SKIP;
    }

    /*
     * The delete logic is pretty complicated now.
     * This is corroborated by the following:
     * 1. The store might be instructed to keep deleted rows around.
     * 2. A scan can optionally see past a delete marker now.
     * 3. If deleted rows are kept, we have to find out when we can
     *    remove the delete markers.
     * 4. Family delete markers are always first (regardless of their TS)
     * 5. Delete markers should not be counted as version
     * 6. Delete markers affect puts of the *same* TS
     * 7. Delete marker need to be version counted together with puts
     *    they affect
     */
    byte typeByte = cell.getTypeByte();
    long mvccVersion = cell.getSequenceId();
    if (CellUtil.isDelete(cell)) {
      if (keepDeletedCells == KeepDeletedCells.FALSE
          || (keepDeletedCells == KeepDeletedCells.TTL && timestamp < ttl)) {
        // first ignore delete markers if the scanner can do so, and the
        // range does not include the marker
        //
        // during flushes and compactions also ignore delete markers newer
        // than the readpoint of any open scanner, this prevents deleted
        // rows that could still be seen by a scanner from being collected
        boolean includeDeleteMarker = seePastDeleteMarkers ?
            tr.withinTimeRange(timestamp) :
            tr.withinOrAfterTimeRange(timestamp);
        if (includeDeleteMarker
            && mvccVersion <= maxReadPointToTrackVersions) {
          this.deletes.add(cell);
        }
        // Can't early out now, because DelFam come before any other keys
      }

      if ((!isUserScan)
          && timeToPurgeDeletes > 0
          && (EnvironmentEdgeManager.currentTime() - timestamp)
            <= timeToPurgeDeletes) {
        return MatchCode.INCLUDE;
      } else if (retainDeletesInOutput || mvccVersion > maxReadPointToTrackVersions) {
        // always include or it is not time yet to check whether it is OK
        // to purge deltes or not
        if (!isUserScan) {
          // if this is not a user scan (compaction), we can filter this deletemarker right here
          // otherwise (i.e. a "raw" scan) we fall through to normal version and timerange checking
          return MatchCode.INCLUDE;
        }
      } else if (keepDeletedCells == KeepDeletedCells.TRUE
          || (keepDeletedCells == KeepDeletedCells.TTL && timestamp >= ttl)) {
        if (timestamp < earliestPutTs) {
          // keeping delete rows, but there are no puts older than
          // this delete in the store files.
          return columns.getNextRowOrNextColumn(cell);
        }
        // else: fall through and do version counting on the
        // delete markers
      } else {
        return MatchCode.SKIP;
      }
      // note the following next else if...
      // delete marker are not subject to other delete markers
    } else if (!this.deletes.isEmpty()) {
      DeleteResult deleteResult = deletes.isDeleted(cell);
      switch (deleteResult) {
        case FAMILY_DELETED:
        case COLUMN_DELETED:
          return columns.getNextRowOrNextColumn(cell);
        case VERSION_DELETED:
        case FAMILY_VERSION_DELETED:
          return MatchCode.SKIP;
        case NOT_DELETED:
          break;
        default:
          throw new RuntimeException("UNEXPECTED");
        }
    }

    // NOTE: Cryptic stuff!
    // if the timestamp is HConstants.OLDEST_TIMESTAMP, then this is a fake cell made to prime a
    // Scanner; See KeyValueUTil#createLastOnRow. This Cell should never end up returning out of
    // here a matchcode of INCLUDE else we will return to the client a fake Cell. If we call
    // TimeRange, it will return 0 because it doesn't deal in OLDEST_TIMESTAMP and we will fall
    // into the later code where we could return a matchcode of INCLUDE. See HBASE-16074 "ITBLL
    // fails, reports lost big or tiny families" for a horror story. Check here for
    // OLDEST_TIMESTAMP. TimeRange#compare is about more generic timestamps, between 0L and
    // Long.MAX_LONG. It doesn't do OLDEST_TIMESTAMP weird handling.
    int timestampComparison = timestamp == HConstants.OLDEST_TIMESTAMP? -1: tr.compare(timestamp);
    if (timestampComparison >= 1) {
      return MatchCode.SKIP;
    } else if (timestampComparison <= -1) {
      return columns.getNextRowOrNextColumn(cell);
    }

    // STEP 1: Check if the column is part of the requested columns
    MatchCode colChecker = columns.checkColumn(cell, typeByte);
    if (colChecker == MatchCode.INCLUDE) {
      ReturnCode filterResponse = ReturnCode.SKIP;
      // STEP 2: Yes, the column is part of the requested columns. Check if filter is present
      if (filter != null) {
        // STEP 3: Filter the key value and return if it filters out
        filterResponse = filter.filterKeyValue(cell);
        switch (filterResponse) {
        case SKIP:
          return MatchCode.SKIP;
        case NEXT_COL:
          return columns.getNextRowOrNextColumn(cell);
        case NEXT_ROW:
          stickyNextRow = true;
          return MatchCode.SEEK_NEXT_ROW;
        case SEEK_NEXT_USING_HINT:
          return MatchCode.SEEK_NEXT_USING_HINT;
        default:
          //It means it is either include or include and seek next
          break;
        }
      }
      /*
       * STEP 4: Reaching this step means the column is part of the requested columns and either
       * the filter is null or the filter has returned INCLUDE or INCLUDE_AND_NEXT_COL response.
       * Now check the number of versions needed. This method call returns SKIP, INCLUDE,
       * INCLUDE_AND_SEEK_NEXT_ROW, INCLUDE_AND_SEEK_NEXT_COL.
       *
       * FilterResponse            ColumnChecker               Desired behavior
       * INCLUDE                   SKIP                        row has already been included, SKIP.
       * INCLUDE                   INCLUDE                     INCLUDE
       * INCLUDE                   INCLUDE_AND_SEEK_NEXT_COL   INCLUDE_AND_SEEK_NEXT_COL
       * INCLUDE                   INCLUDE_AND_SEEK_NEXT_ROW   INCLUDE_AND_SEEK_NEXT_ROW
       * INCLUDE_AND_SEEK_NEXT_COL SKIP                        row has already been included, SKIP.
       * INCLUDE_AND_SEEK_NEXT_COL INCLUDE                     INCLUDE_AND_SEEK_NEXT_COL
       * INCLUDE_AND_SEEK_NEXT_COL INCLUDE_AND_SEEK_NEXT_COL   INCLUDE_AND_SEEK_NEXT_COL
       * INCLUDE_AND_SEEK_NEXT_COL INCLUDE_AND_SEEK_NEXT_ROW   INCLUDE_AND_SEEK_NEXT_ROW
       *
       * In all the above scenarios, we return the column checker return value except for
       * FilterResponse (INCLUDE_AND_SEEK_NEXT_COL) and ColumnChecker(INCLUDE)
       */
      colChecker = columns.checkVersions(cell, timestamp, typeByte,
          mvccVersion > maxReadPointToTrackVersions);
      //Optimize with stickyNextRow
      boolean seekNextRowFromEssential = filterResponse == ReturnCode.INCLUDE_AND_SEEK_NEXT_ROW &&
          filter.isFamilyEssential(cell.getFamilyArray());
      if (colChecker == MatchCode.INCLUDE_AND_SEEK_NEXT_ROW || seekNextRowFromEssential) {
        stickyNextRow = true;
      }
      if (filterResponse == ReturnCode.INCLUDE_AND_SEEK_NEXT_ROW) {
        if (colChecker != MatchCode.SKIP) {
          return MatchCode.INCLUDE_AND_SEEK_NEXT_ROW;
        }
        return MatchCode.SEEK_NEXT_ROW;
      }
      return (filterResponse == ReturnCode.INCLUDE_AND_NEXT_COL &&
          colChecker == MatchCode.INCLUDE) ? MatchCode.INCLUDE_AND_SEEK_NEXT_COL
          : colChecker;
    }
    stickyNextRow = (colChecker == MatchCode.SEEK_NEXT_ROW) ? true
        : stickyNextRow;
    return colChecker;
  }

  /** Handle partial-drop-deletes. As we match keys in order, when we have a range from which
   * we can drop deletes, we can set retainDeletesInOutput to false for the duration of this
   * range only, and maintain consistency. */
  private void checkPartialDropDeleteRange(Cell curCell) {
    // If partial-drop-deletes are used, initially, dropDeletesFromRow and dropDeletesToRow
    // are both set, and the matcher is set to retain deletes. We assume ordered keys. When
    // dropDeletesFromRow is leq current kv, we start dropping deletes and reset
    // dropDeletesFromRow; thus the 2nd "if" starts to apply.
    if ((dropDeletesFromRow != null)
        && (Arrays.equals(dropDeletesFromRow, HConstants.EMPTY_START_ROW) ||
            (CellComparator.COMPARATOR.compareRows(curCell, dropDeletesFromRow, 0,
                dropDeletesFromRow.length) >= 0))) {
      retainDeletesInOutput = false;
      dropDeletesFromRow = null;
    }
    // If dropDeletesFromRow is null and dropDeletesToRow is set, we are inside the partial-
    // drop-deletes range. When dropDeletesToRow is leq current kv, we stop dropping deletes,
    // and reset dropDeletesToRow so that we don't do any more compares.
    if ((dropDeletesFromRow == null)
        && (dropDeletesToRow != null)
        && !Arrays.equals(dropDeletesToRow, HConstants.EMPTY_END_ROW)
        && (CellComparator.COMPARATOR
            .compareRows(curCell, dropDeletesToRow, 0, dropDeletesToRow.length) >= 0)) {
      retainDeletesInOutput = true;
      dropDeletesToRow = null;
    }
  }

  /**
   * @return Returns false if we know there are no more rows to be scanned (We've reached the
   * <code>stopRow</code> or we are scanning on row only because this Scan is for a Get, etc.
   */
  public boolean moreRowsMayExistAfter(Cell kv) {
    // If a 'get' Scan -- we are doing a Get (every Get is a single-row Scan in implementation) --
    // then we are looking at one row only, the one specified in the Get coordinate..so we know
    // for sure that there are no more rows on this Scan
    if (this.get) {
      return false;
    }
    // If no stopRow, return that there may be more rows. The tests that follow depend on a
    // non-empty, non-default stopRow so this little test below short-circuits out doing the
    // following compares.
    if (this.stopRow == null || this.stopRow == HConstants.EMPTY_BYTE_ARRAY) {
      return true;
    }
    return this.isReversed?
      rowComparator.compareRows(kv, stopRow, 0, stopRow.length) > 0:
      Bytes.equals(stopRow, HConstants.EMPTY_END_ROW) ||
        rowComparator.compareRows(kv, stopRow, 0, stopRow.length) < 0;
  }

  /**
   * Set the row when there is change in row
   * @param curCell
   */
  public void setToNewRow(Cell curCell) {
    checkPartialDropDeleteRange(curCell);
    this.curCell = curCell;
    reset();
  }

  public void reset() {
    this.deletes.reset();
    this.columns.reset();

    stickyNextRow = false;
  }

  /**
   *
   * @return the start key
   */
  public Cell getStartKey() {
    return this.startKey;
  }

  /**
   *
   * @return the Filter
   */
  Filter getFilter() {
    return this.filter;
  }

  public Cell getNextKeyHint(Cell kv) throws IOException {
    if (filter == null) {
      return null;
    } else {
      return filter.getNextCellHint(kv);
    }
  }

  public Cell getKeyForNextColumn(Cell kv) {
    ColumnCount nextColumn = columns.getColumnHint();
    if (nextColumn == null) {
      return CellUtil.createLastOnRowCol(kv);
    } else {
      return CellUtil.createFirstOnRowCol(kv, nextColumn.getBuffer(), nextColumn.getOffset(),
          nextColumn.getLength());
    }
  }

  public Cell getKeyForNextRow(Cell c) {
    return CellUtil.createLastOnRow(c);
  }

  /**
   * @param nextIndexed the key of the next entry in the block index (if any)
   * @param kv The Cell we're using to calculate the seek key
   * @return result of the compare between the indexed key and the key portion of the passed cell
   */
  public int compareKeyForNextRow(Cell nextIndexed, Cell kv) {
    return rowComparator.compareKeyBasedOnColHint(nextIndexed, kv, 0, 0, null, 0, 0,
        HConstants.OLDEST_TIMESTAMP, Type.Minimum.getCode());
  }

  /**
   * @param nextIndexed the key of the next entry in the block index (if any)
   * @param currentCell The Cell we're using to calculate the seek key
   * @return result of the compare between the indexed key and the key portion of the passed cell
   */
  public int compareKeyForNextColumn(Cell nextIndexed, Cell currentCell) {
    ColumnCount nextColumn = columns.getColumnHint();
    if (nextColumn == null) {
      return rowComparator.compareKeyBasedOnColHint(nextIndexed, currentCell, 0, 0, null, 0, 0,
          HConstants.OLDEST_TIMESTAMP, Type.Minimum.getCode());
    } else {
      return rowComparator.compareKeyBasedOnColHint(nextIndexed, currentCell,
          currentCell.getFamilyOffset(), currentCell.getFamilyLength(), nextColumn.getBuffer(),
          nextColumn.getOffset(), nextColumn.getLength(), HConstants.LATEST_TIMESTAMP,
          Type.Maximum.getCode());
    }
  }

  boolean isUserScan() {
    return this.isUserScan;
  }

  //Used only for testing purposes
  static MatchCode checkColumn(ColumnTracker columnTracker, byte[] bytes, int offset,
      int length, long ttl, byte type, boolean ignoreCount) throws IOException {
    KeyValue kv = KeyValueUtil.createFirstOnRow(HConstants.EMPTY_BYTE_ARRAY, 0, 0,
        HConstants.EMPTY_BYTE_ARRAY, 0, 0, bytes, offset, length);
    MatchCode matchCode = columnTracker.checkColumn(kv, type);
    if (matchCode == MatchCode.INCLUDE) {
      return columnTracker.checkVersions(kv, ttl, type, ignoreCount);
    }
    return matchCode;
  }

  /**
   * {@link #match} return codes.  These instruct the scanner moving through
   * memstores and StoreFiles what to do with the current KeyValue.
   * <p>
   * Additionally, this contains "early-out" language to tell the scanner to
   * move on to the next File (memstore or Storefile), or to return immediately.
   */
  public static enum MatchCode {
    /**
     * Include KeyValue in the returned result
     */
    INCLUDE,

    /**
     * Do not include KeyValue in the returned result
     */
    SKIP,

    /**
     * Do not include, jump to next StoreFile or memstore (in time order)
     */
    NEXT,

    /**
     * Do not include, return current result
     */
    DONE,

    /**
     * These codes are used by the ScanQueryMatcher
     */

    /**
     * Done with the row, seek there.
     */
    SEEK_NEXT_ROW,
    /**
     * Done with column, seek to next.
     */
    SEEK_NEXT_COL,

    /**
     * Done with scan, thanks to the row filter.
     */
    DONE_SCAN,

    /*
     * Seek to next key which is given as hint.
     */
    SEEK_NEXT_USING_HINT,

    /**
     * Include KeyValue and done with column, seek to next.
     */
    INCLUDE_AND_SEEK_NEXT_COL,

    /**
     * Include KeyValue and done with row, seek to next.
     */
    INCLUDE_AND_SEEK_NEXT_ROW,
  }
}
