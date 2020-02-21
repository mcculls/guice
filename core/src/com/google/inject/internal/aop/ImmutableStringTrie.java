/*
 * Copyright (C) 2019 Google Inc.
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
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.inject.internal.aop;

import static java.util.Arrays.binarySearch;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Immutable space-efficient trie that provides a fast index for a sorted list of strings. It
 * assumes only those strings will be queried and therefore may produce false-positive results for
 * strings not in the list.
 *
 * <p>Each node of the tree is represented as a series of {@code char}s using this layout:
 *
 * <pre>
 * +---------------------------------+
 * | number of branches              |
 * +---------------------------------+---------------------------------+----
 * | char for branch 0               | char for branch 1               | ...
 * +---------------------------------+---------------------------------+----
 * | key-delta/leaf/bud for branch 0 | key-delta/leaf/bud for branch 1 | ...
 * +---------------------------------+---------------------------------+----
 * | offset to jump to branch 1      | offset to jump to branch 2      | ...
 * +---------------------------------+---------------------------------+----
 * </pre>
 *
 * Each node is immediately followed by its child nodes according to branch order.
 *
 * <p>The key-delta is used to skip over a section of the input key when we know it should always
 * match given the recently matched char (assumes only strings from the original list are queried).
 *
 * <p>Leaves mark a definite end of the match, while buds mark a potential end which could continue
 * down the trie if there are more characters to match. The key-delta for buds is implicitly 1.
 *
 * <p>The jump section is omitted when all the branches from a node are leaves.
 *
 * <p>Simple example: getValue, setValue
 *
 * <pre>
 * +---+---+---+--------+--------+
 * | 2 | g | s | 0x8000 | 0x8001 |
 * +---+---+---+--------+--------+
 * </pre>
 *
 * In this case the first character is enough to determine the index result.
 *
 * <p>Example of a trie with a 'bud': getName, getNameAndValue
 *
 * <pre>
 * +---+---+---+---+---+--------+---+---+--------+
 * | 1 | g | 6 | 1 | e | 0x4000 | 1 | A | 0x8001 |
 * +---+---+---+---+---+--------+---+---+--------+
 * </pre>
 *
 * After matching 'g' we skip to the end of 'getName' before checking if there are any more
 * characters to match.
 *
 * <p>More complex example: getName, getValue, getVersion
 *
 * <pre>
 * +---+---+---+---+---+---+--------+---+---+---+---+---+--------+--------+
 * | 1 | g | 3 | 2 | N | V | 0x8000 | 1 | 0 | 2 | a | e | 0x8001 | 0x8002 |
 * +---+---+---+---+---+---+--------+---+---+---+---+---+--------+--------+
 * </pre>
 *
 * @author mcculls@gmail.com (Stuart McCulloch)
 */
final class ImmutableStringTrie implements ToIntFunction<String> {

  /** Marks a leaf in the trie, where the rest of the bits are the index to be returned. */
  private static final char LEAF_MARKER = 0x8000;

  /** Marks a 'bud' in the tree; the same as a leaf except the trie continues beneath it. */
  private static final char BUD_MARKER = 0x4000;

  /** Maximum number of rows that can be indexed by a single trie. */
  private static final int MAX_ROWS_PER_TRIE = 0x4000;

  /** The compressed trie. */
  private final char[] data;

  /**
   * Returns the index assigned in the trie to the given string.
   *
   * <p>Note: a return value of {@code -1} means the string is definitely not in the trie, but a
   * non-negative index may be returned for strings that closely match those in the trie. This is
   * acceptable because we will only call this method with strings that we know exist in the trie.
   */
  @Override
  public int applyAsInt(String key) {
    int keyLength = key.length();

    int keyIndex = 0;
    int dataIndex = 0;

    while (keyIndex < keyLength) {
      // trie is ordered, so we can use binary search to pick the right branch
      int branchCount = data[dataIndex++];
      int branchIndex =
          binarySearch(data, dataIndex, dataIndex + branchCount, key.charAt(keyIndex));

      if (branchIndex < 0) {
        break; // definitely no match
      }

      int resultIndex = branchIndex + branchCount;
      char result = data[resultIndex];
      if ((result & LEAF_MARKER) != 0) {
        return result & ~LEAF_MARKER;
      }

      // 'buds' are just like leaves unless the key still has characters left
      if ((result & BUD_MARKER) != 0) {
        if (keyIndex == keyLength - 1) {
          return result & ~BUD_MARKER;
        }
        result = 1; // more characters to match, continue search with next character
      }

      // move the key to the next potential decision point
      keyIndex += result;

      // move the data to the appropriate branch...
      if (branchIndex > dataIndex) {
        int jumpIndex = resultIndex + branchCount - 1;
        dataIndex += data[jumpIndex];
      }

      // ...always include moving past the current node
      dataIndex += (branchCount * 3) - 1;
    }

    return -1;
  }

  /**
   * Builds an immutable trie that indexes the given table of strings.
   *
   * <p>The table of strings must be sorted in lexical order.
   */
  public static ToIntFunction<String> build(List<String> table) {
    return build(new StringBuilder(), table, 0, table.size());
  }

  /** Builds a trie, overflowing to additional tries if there are too many rows */
  private static ToIntFunction<String> build(
      StringBuilder buf, List<String> table, int row, int rowLimit) {

    int trieLimit = row + MAX_ROWS_PER_TRIE;
    boolean overflow = rowLimit > trieLimit;

    buildTrie(buf, table, 0, row, overflow ? trieLimit : rowLimit);

    char[] data = new char[buf.length()];
    buf.getChars(0, data.length, data, 0);
    buf.setLength(0);

    return overflow
        ? new Overflow(data, table.get(trieLimit), build(buf, table, trieLimit, rowLimit))
        : new ImmutableStringTrie(data);
  }

  ImmutableStringTrie(char[] data) {
    this.data = data;
  }

  /** Recursively builds a trie for a slice of rows at a particular column. */
  private static void buildTrie(
      StringBuilder buf, List<String> table, int column, int row, int rowLimit) {

    int trieStart = buf.length();

    int lastRow = row;
    int branchCount = 0;
    int nextJump = 0;

    boolean allLeaves = true;

    while (lastRow < rowLimit) {
      String cells = table.get(lastRow);
      int columnLimit = cells.length();

      char pivot = cells.charAt(column);

      // find the row that marks the start of the next branch, and the end of this one
      int nextRow = nextPivotRow(table, pivot, column, lastRow, rowLimit);

      // find the column along this branch that marks the next decision point/pivot
      int nextColumn = nextPivotColumn(table, column, lastRow, nextRow);

      // at the end of our row, check in case there are further rows that we'd normally
      // handle with a bud, but we can't because our branch spans more than one column
      if (nextColumn == columnLimit && nextColumn - column > 1 && nextRow - lastRow > 1) {
        // set the next column to just before the end of our row so we can insert a bud
        nextColumn--;
      }

      int branchIndex = trieStart + branchCount;
      buf.insert(branchIndex, pivot);

      int resultIndex = branchIndex + 1 + branchCount;

      // sub trie will start after the result (to be inserted)
      int subTrieStart = buf.length() + 1;

      if (nextColumn < columnLimit) {
        buf.insert(resultIndex, (char) (nextColumn - column));
        buildTrie(buf, table, nextColumn, lastRow, nextRow);
        allLeaves = false;
      } else {
        buildTrie(buf, table, nextColumn, lastRow + 1, nextRow);
        boolean isLeaf = subTrieStart > buf.length(); // only true if nothing was added
        char marker = isLeaf ? LEAF_MARKER : BUD_MARKER;
        buf.insert(resultIndex, (char) (lastRow & (MAX_ROWS_PER_TRIE - 1) | marker));
        allLeaves = allLeaves && isLeaf;
      }

      if (nextRow < rowLimit) {
        int jumpIndex = resultIndex + 1 + branchCount;
        nextJump += buf.length() - subTrieStart;
        buf.insert(jumpIndex, (char) nextJump);
      }

      lastRow = nextRow;
      branchCount++;
    }

    if (branchCount > 0) {
      buf.insert(trieStart, (char) branchCount);
      if (allLeaves) {
        // no need for jumps when every branch is a leaf
        int jumpStart = trieStart + 1 + (branchCount * 2);
        buf.delete(jumpStart, jumpStart + branchCount);
      }
    }
  }

  /**
   * Finds the next row that has a different character in the selected column to the given one, or
   * is too short to include the column. This determines the span of rows that fall under the given
   * character in the trie.
   *
   * <p>Returns the row just after the end of the range if all rows have the same character.
   */
  private static int nextPivotRow(
      List<String> table, char pivot, int column, int row, int rowLimit) {

    for (int r = row + 1; r < rowLimit; r++) {
      String cells = table.get(r);
      if (cells.length() <= column || cells.charAt(column) != pivot) {
        return r;
      }
    }

    return rowLimit;
  }

  /**
   * Finds the next column in the current row whose character differs in at least one other row.
   * This helps identify the longest common prefix from the current pivot point to the next one.
   *
   * <p>Returns the column just after the end of the current row if all rows are identical.
   */
  private static int nextPivotColumn(List<String> table, int column, int row, int rowLimit) {

    String cells = table.get(row);
    int columnLimit = cells.length();

    for (int c = column + 1; c < columnLimit; c++) {
      if (nextPivotRow(table, cells.charAt(c), c, row, rowLimit) < rowLimit) {
        return c;
      }
    }

    return columnLimit;
  }

  /** Immutable trie that delegates searches that lie outside its range to an overflow trie. */
  private static final class Overflow implements ToIntFunction<String> {
    private final ImmutableStringTrie trie;

    private final String overflowKey;

    private final ToIntFunction<String> next;

    Overflow(char[] data, String overflowKey, ToIntFunction<String> next) {
      this.trie = new ImmutableStringTrie(data);
      this.overflowKey = overflowKey;
      this.next = next;
    }

    @Override
    public int applyAsInt(String key) {
      return key.compareTo(overflowKey) < 0
          ? trie.applyAsInt(key)
          : MAX_ROWS_PER_TRIE + next.applyAsInt(key);
    }
  }
}
