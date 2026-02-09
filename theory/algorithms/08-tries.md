# Tries (Prefix Trees)

## Overview

A Trie is a tree-like data structure optimized for string prefix operations. Each node represents a character, and paths from root to marked nodes represent stored words. Tries excel at autocomplete, spell checking, prefix matching, and word search problems.

## Core Concepts

### Structure

```
Root
├── a
│   ├── p
│   │   ├── p (word: "app")
│   │   │   └── l
│   │   │       └── e (word: "apple")
│   │   └── e (word: "ape")
│   └── t (word: "at")
└── t
    └── o (word: "to")
```

### Implementation

```java
class TrieNode {
    TrieNode[] children = new TrieNode[26]; // assuming lowercase a-z
    boolean isWord = false;
    // Optional: store the word itself, frequency count, etc.
    // String word;
    // int count;
}

class Trie {
    TrieNode root = new TrieNode();

    // Insert — O(m) where m = word length
    public void insert(String word) {
        TrieNode node = root;
        for (char c : word.toCharArray()) {
            int i = c - 'a';
            if (node.children[i] == null) {
                node.children[i] = new TrieNode();
            }
            node = node.children[i];
        }
        node.isWord = true;
    }

    // Search exact word — O(m)
    public boolean search(String word) {
        TrieNode node = findNode(word);
        return node != null && node.isWord;
    }

    // Search prefix — O(m)
    public boolean startsWith(String prefix) {
        return findNode(prefix) != null;
    }

    private TrieNode findNode(String s) {
        TrieNode node = root;
        for (char c : s.toCharArray()) {
            int i = c - 'a';
            if (node.children[i] == null) return null;
            node = node.children[i];
        }
        return node;
    }
}
```

### Time and Space Complexity

| Operation     | Time   | Notes                        |
|---------------|--------|------------------------------|
| Insert        | O(m)   | m = length of the word       |
| Search        | O(m)   |                              |
| Prefix search | O(m)   |                              |
| Delete        | O(m)   |                              |
| Space         | O(N*M) | N words, M avg length (worst case) |

**Trie vs. HashMap:**
- Trie: O(m) search, prefix-friendly, space efficient for shared prefixes
- HashMap: O(m) search (hashing), no prefix support, simpler code

### HashMap-Based Trie (Alternative)

```java
class TrieNode {
    Map<Character, TrieNode> children = new HashMap<>();
    boolean isWord = false;
}
// More flexible (supports unicode, larger alphabets)
// Slightly slower due to HashMap overhead
```

## Essential Patterns

### 1. Word Search II (Trie + Backtracking on Grid)

The classic hard Trie problem — find all words from a dictionary in a 2D grid.

```java
class Solution {
    int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};
    List<String> result = new ArrayList<>();

    public List<String> findWords(char[][] board, String[] words) {
        TrieNode root = buildTrie(words);
        for (int i = 0; i < board.length; i++) {
            for (int j = 0; j < board[0].length; j++) {
                dfs(board, i, j, root);
            }
        }
        return result;
    }

    private void dfs(char[][] board, int r, int c, TrieNode node) {
        if (r < 0 || r >= board.length || c < 0 || c >= board[0].length) return;
        char ch = board[r][c];
        if (ch == '#' || node.children[ch - 'a'] == null) return;

        node = node.children[ch - 'a'];
        if (node.word != null) {
            result.add(node.word);
            node.word = null; // deduplicate
        }

        board[r][c] = '#'; // mark visited
        for (int[] d : dirs) dfs(board, r + d[0], c + d[1], node);
        board[r][c] = ch;  // restore
    }

    private TrieNode buildTrie(String[] words) {
        TrieNode root = new TrieNode();
        for (String w : words) {
            TrieNode node = root;
            for (char c : w.toCharArray()) {
                int i = c - 'a';
                if (node.children[i] == null) node.children[i] = new TrieNode();
                node = node.children[i];
            }
            node.word = w;
        }
        return root;
    }
}

class TrieNode {
    TrieNode[] children = new TrieNode[26];
    String word; // store the word at terminal nodes
}
```

### 2. Autocomplete / Search Suggestions

```java
// Collect all words with a given prefix
public List<String> autocomplete(String prefix) {
    TrieNode node = findNode(prefix);
    List<String> results = new ArrayList<>();
    if (node != null) {
        collectWords(node, new StringBuilder(prefix), results);
    }
    return results;
}

private void collectWords(TrieNode node, StringBuilder sb, List<String> results) {
    if (node.isWord) results.add(sb.toString());
    for (int i = 0; i < 26; i++) {
        if (node.children[i] != null) {
            sb.append((char) ('a' + i));
            collectWords(node.children[i], sb, results);
            sb.deleteCharAt(sb.length() - 1);
        }
    }
}
```

### 3. Design Add and Search Words (Wildcard Search)

```java
// '.' matches any single character — use DFS
public boolean search(String word) {
    return searchHelper(word, 0, root);
}

private boolean searchHelper(String word, int idx, TrieNode node) {
    if (idx == word.length()) return node.isWord;

    char c = word.charAt(idx);
    if (c == '.') {
        // Try all children
        for (TrieNode child : node.children) {
            if (child != null && searchHelper(word, idx + 1, child)) {
                return true;
            }
        }
        return false;
    } else {
        TrieNode child = node.children[c - 'a'];
        return child != null && searchHelper(word, idx + 1, child);
    }
}
```

### 4. Longest Common Prefix

```java
public String longestCommonPrefix(String[] strs) {
    Trie trie = new Trie();
    for (String s : strs) trie.insert(s);

    StringBuilder sb = new StringBuilder();
    TrieNode node = trie.root;
    while (true) {
        int childCount = 0, nextIdx = -1;
        for (int i = 0; i < 26; i++) {
            if (node.children[i] != null) { childCount++; nextIdx = i; }
        }
        if (childCount != 1 || node.isWord) break;
        sb.append((char) ('a' + nextIdx));
        node = node.children[nextIdx];
    }
    return sb.toString();
}
```

### 5. Counting Prefixes / Words

```java
class CountingTrie {
    int[][] children;
    int[] wordCount;
    int[] prefixCount;
    int cnt = 0;

    CountingTrie(int maxNodes) {
        children = new int[maxNodes][26];
        wordCount = new int[maxNodes];
        prefixCount = new int[maxNodes];
        for (int[] row : children) Arrays.fill(row, -1);
    }

    void insert(String word) {
        int node = 0;
        for (char c : word.toCharArray()) {
            int i = c - 'a';
            if (children[node][i] == -1) children[node][i] = ++cnt;
            node = children[node][i];
            prefixCount[node]++;
        }
        wordCount[node]++;
    }
}
```

## Common Interview Problems

### Easy
- Implement Trie (Prefix Tree)
- Longest Common Prefix (can use Trie or simpler approaches)

### Medium
- Design Add and Search Words Data Structure
- Replace Words, Map Sum Pairs
- Search Suggestions System
- Longest Word in Dictionary

### Hard
- Word Search II (Trie + backtracking)
- Palindrome Pairs, Word Squares
- Stream of Characters

## Tips and Pitfalls

- **Array vs. HashMap children:** Use `TrieNode[26]` for lowercase-only (faster). Use `HashMap` for larger character sets or sparse tries.
- **Store the word at terminal nodes** instead of just a boolean — avoids reconstructing the word during DFS (see Word Search II).
- **Pruning for optimization:** After finding a word, remove it from the trie to avoid duplicates and reduce future search space.
- **Space optimization:** In practice, tries can use a lot of memory. Consider compressed tries (radix trees) if space is a concern, though this rarely comes up in interviews.
- **Trie vs. HashSet for dictionary:** If you only need exact match, use HashSet. Tries shine when you need prefix operations.
- **Deletion is rarely asked** but know conceptually: traverse to the node, unmark `isWord`, and optionally prune childless nodes.
