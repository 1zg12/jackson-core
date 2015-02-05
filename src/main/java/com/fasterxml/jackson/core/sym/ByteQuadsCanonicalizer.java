package com.fasterxml.jackson.core.sym;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.util.InternCache;

/**
 * Replacement for {@link BytesToNameCanonicalizer} which aims at more localized
 * memory access due to flattening of name quad data.
 *
 * @since 2.6
 */
public final class ByteQuadsCanonicalizer
{
    /**
     * Initial size of the primary hash area. Each entry consumes 4 ints (16 bytes),
     * and secondary area is same as primary; so default size will use 2kB of memory
     * (plus 64x4 or 64x8 (256/512 bytes) for references to Strings, and Strings
     * themselves).
     */
    private static final int DEFAULT_T_SIZE = 64;

    /**
     * Let's not expand symbol tables past some maximum size;
     * this should protected against OOMEs caused by large documents
     * with unique (~= random) names.
     * Size is in 
     */
    private static final int MAX_T_SIZE = 0x10000; // 64k entries == 2M mem hash area

    /**
     * No point in trying to construct tiny tables, just need to resize soon.
     */
    final static int MIN_HASH_SIZE = 16;
    
    /**
     * Let's only share reasonably sized symbol tables. Max size set to 3/4 of 16k;
     * this corresponds to 64k main hash index. This should allow for enough distinct
     * names for almost any case.
     */
    private final static int MAX_ENTRIES_FOR_REUSE = 6000;

    /*
    /**********************************************************
    /* Linkage, needed for merging symbol tables
    /**********************************************************
     */

    /**
     * Reference to the root symbol table, for child tables, so
     * that they can merge table information back as necessary.
     */
    final protected ByteQuadsCanonicalizer _parent;

    /**
     * Member that is only used by the root table instance: root
     * passes immutable state into child instances, and children
     * may return new state if they add entries to the table.
     * Child tables do NOT use the reference.
     */
    final protected AtomicReference<TableInfo> _tableInfo;
    
    /**
     * Seed value we use as the base to make hash codes non-static between
     * different runs, but still stable for lifetime of a single symbol table
     * instance.
     * This is done for security reasons, to avoid potential DoS attack via
     * hash collisions.
     * 
     * @since 2.1
     */
    final private int _seed;
    
    /*
    /**********************************************************
    /* Configuration
    /**********************************************************
     */

    /**
     * Whether canonical symbol Strings are to be intern()ed before added
     * to the table or not.
     *<p>
     * NOTE: non-final to allow disabling intern()ing in case of excessive
     * collisions.
     */
    protected boolean _intern;

    /**
     * Flag that indicates whether we should throw an exception if enough 
     * hash collisions are detected (true); or just worked around (false).
     * 
     * @since 2.4
     */
    protected final boolean _failOnDoS;
    
    /*
    /**********************************************************
    /* First, main hash area info
    /**********************************************************
     */

    /**
     * Primary hash information area: consists of <code>2 * _hashSize</code>
     * entries of 16 bytes (4 ints), arranged in a cascading lookup
     * structure (details of which may be tweaked depending on expected rates
     * of collisions).
     */
    protected int[] _hash;
    
    /**
     * Mask used to truncate 32-bit hash value to current hash array
     * size; essentially, {@link _hashSize} - 1 (since hash array sizes
     * are 2^N).
     */
    protected int _hashMask;

    /**
     * Number of slots for primary entries within {@link #_hash}; which is
     * <code>1/2</code> 
     */
    protected int _hashSize;
    
    /**
     * Total number of Strings in the symbol table; only used for child tables.
     */
    protected int _count;

    /**
     * Array that contains <code>String</code> instances matching
     * entries in {@link #_hash}.
     * Contains nulls for unused entries. Note that this size is twice
     * that o
     */
    protected String[] _names;

    /*
    /**********************************************************
    /* Then information on collisions etc
    /**********************************************************
     */

    /**
     * Number of entries that ended up in the shared spill-over
     * area (that is, did not fit in primary, secondary or tertiary slots).
     */
    protected int _spillOverCount;

    /**
     * Offset within {@link #_hash} that follows main slots and contains
     * quads for longer names (13 bytes or longers), and points to the
     * first available int that may be used for appending quads of the next
     * long name.
     */
    protected int _longNameOffset;

    /**
     * We need to keep track of the longest collision list; this is needed
     * both to indicate problems with attacks and to allow flushing for
     * other cases.
     */
    protected int _longestCollisionList;

    /**
     * Total number of Names in collision buckets (included in
     * <code>_count</code> along with primary entries)
     */
    protected int _collCount;

    /**
     * This flag is set if, after adding a new entry, it is deemed
     * that a rehash is warranted if any more entries are to be added.
     */
    private transient boolean _needRehash;

    /*
    /**********************************************************
    /* Sharing, versioning
    /**********************************************************
     */

    // // // Which of the buffers may be shared (and are copy-on-write)?

    /**
     * Flag that indicates whether underlying data structures for
     * the main hash area are shared or not. If they are, then they
     * need to be handled in copy-on-write way, i.e. if they need
     * to be modified, a copy needs to be made first; at this point
     * it will not be shared any more, and can be modified.
     *<p>
     * This flag needs to be checked both when adding new main entries,
     * and when adding new collision list queues (i.e. creating a new
     * collision list head entry)
     */
    private boolean _hashShared;

    /*
    /**********************************************************
    /* Bit of DoS detection goodness
    /**********************************************************
     */

    /**
     * Lazily constructed structure that is used to keep track of
     * collision buckets that have overflowed once: this is used
     * to detect likely attempts at denial-of-service attacks that
     * uses hash collisions.
     * 
     * @since 2.4
     */
    protected BitSet _overflows;
    
    /*
    /**********************************************************
    /* Life-cycle: constructors
    /**********************************************************
     */

    /**
     * Constructor used for creating per-<code>JsonFactory</code> "root"
     * symbol tables: ones used for merging and sharing common symbols
     * 
     * @param sz Initial primary hash area size
     * @param intern Whether Strings contained should be {@link String#intern}ed
     * @param seed Random seed valued used to make it more difficult to cause
     *   collisions (used for collision-based DoS attacks).
     */
    private ByteQuadsCanonicalizer(int sz, boolean intern, int seed, boolean failOnDoS) {
        _parent = null;
        _seed = seed;
        _intern = intern;
        _failOnDoS = failOnDoS;
        // Sanity check: let's now allow hash sizes below certain minimum value
        if (sz < MIN_HASH_SIZE) {
            sz = MIN_HASH_SIZE;
        } else {
            // Also; size must be 2^N; otherwise hash algorithm won't
            // work... so let's just pad it up, if so
            if ((sz & (sz - 1)) != 0) { // only true if it's 2^N
                int curr = MIN_HASH_SIZE;
                while (curr < sz) {
                    curr += curr;
                }
                sz = curr;
            }
        }
        _tableInfo = new AtomicReference<TableInfo>(TableInfo.createInitial(sz));
    }

    /**
     * Constructor used when creating a child instance
     */
    private ByteQuadsCanonicalizer(ByteQuadsCanonicalizer parent, boolean intern,
            int seed, boolean failOnDoS, TableInfo state)
    {
        _parent = parent;
        _seed = seed;
        _intern = intern;
        _failOnDoS = failOnDoS;
        _tableInfo = null; // not used by child tables

        // Then copy shared state
        _count = state.count;
        _hashSize = state.size;
        _hashMask = _hashSize-1;
        _hash = state.mainHash;
        _names = state.names;
        _collCount = state.collCount;
        _longestCollisionList = state.longestCollisionList;

        // and then set other state to reflect sharing status
        _needRehash = false;
        _hashShared = true;
    }

    /*
    /**********************************************************
    /* Life-cycle: factory methods, merging
    /**********************************************************
     */
    
    /**
     * Factory method to call to create a symbol table instance with a
     * randomized seed value.
     */
    public static ByteQuadsCanonicalizer createRoot() {
        /* [Issue-21]: Need to use a variable seed, to thwart hash-collision
         * based attacks.
         */
        long now = System.currentTimeMillis();
        // ensure it's not 0; and might as well require to be odd so:
        int seed = (((int) now) + ((int) (now >>> 32))) | 1;
        return createRoot(seed);
    }

    /**
     * Factory method that should only be called from unit tests, where seed
     * value should remain the same.
     */
    protected static ByteQuadsCanonicalizer createRoot(int seed) {
        return new ByteQuadsCanonicalizer(DEFAULT_T_SIZE, true, seed, true);
    }
    
    /**
     * Factory method used to create actual symbol table instance to
     * use for parsing.
     */
    public ByteQuadsCanonicalizer makeChild(int flags) {
        return new ByteQuadsCanonicalizer(this,
                JsonFactory.Feature.INTERN_FIELD_NAMES.enabledIn(flags),
                _seed,
                JsonFactory.Feature.FAIL_ON_SYMBOL_HASH_OVERFLOW.enabledIn(flags),
                _tableInfo.get());
    }

    /**
     * Method called by the using code to indicate it is done
     * with this instance. This lets instance merge accumulated
     * changes into parent (if need be), safely and efficiently,
     * and without calling code having to know about parent
     * information
     */
    public void release()
    {
        // we will try to merge if child table has new entries
        if (_parent != null && maybeDirty()) {
            _parent.mergeChild(new TableInfo(this));
            /* Let's also mark this instance as dirty, so that just in
             * case release was too early, there's no corruption of possibly shared data.
             */
            _hashShared = true;
        }
    }

    private void mergeChild(TableInfo childState)
    {
        final int childCount = childState.count;
        TableInfo currState = _tableInfo.get();

        // Should usually grow; but occasionally could also shrink if (but only if)
        // collision list overflow ends up clearing some collision lists.
        if (childCount == currState.count) {
            return;
        }

        // One caveat: let's try to avoid problems with degenerate cases of documents with
        // generated "random" names: for these, symbol tables would bloat indefinitely.
        // One way to do this is to just purge tables if they grow
        // too large, and that's what we'll do here.
        if (childCount > MAX_ENTRIES_FOR_REUSE) {
            // At any rate, need to clean up the tables
            childState = TableInfo.createInitial(DEFAULT_T_SIZE);
        }
        _tableInfo.compareAndSet(currState, childState);
    }

    /*
    /**********************************************************
    /* API, accessors
    /**********************************************************
     */

    public int size()
    {
        if (_tableInfo != null) { // root table
            return _tableInfo.get().count;
        }
        // nope, child table
        return _count;
    }

    /**
     * Returns number of primary slots table has currently
     */
    public int bucketCount() { return _hashSize; }

    /**
     * Method called to check to quickly see if a child symbol table
     * may have gotten additional entries. Used for checking to see
     * if a child table should be merged into shared table.
     */
    public boolean maybeDirty() { return !_hashShared; }

    public int hashSeed() { return _seed; }
    
    /**
     * Method mostly needed by unit tests; calculates number of
     * entries that are in collision list. Value can be at most
     * ({@link #size} - 1), but should usually be much lower, ideally 0.
     */
    public int collisionCount() { return _collCount; }

    /**
     * Method mostly needed by unit tests; calculates length of the
     * longest collision chain. This should typically be a low number,
     * but may be up to {@link #size} - 1 in the pathological case
     */
    public int maxCollisionLength() {
        return _longestCollisionList;
    }

    /*
    /**********************************************************
    /* Public API, accessing symbols
    /**********************************************************
     */

    public String findName(int q1)
    {
        int offset = _calcOffset(calcHash(q1));
        // first: primary match?
        final int[] hashArea = _hash;

        int q1b = hashArea[offset];
        int len = hashArea[offset+3];
        
        if ((q1b == q1) && (len == 1)) {
            return _names[offset >> 4];
        }
        if (len == 0) { // empty slot; unlikely but avoid further lookups if so
            return null;
        }
        // secondary? single slot shared by N/2 primaries
        int offset2 = _hashSize + (offset>>1);

        q1b = hashArea[offset2];
        len = hashArea[offset2+3];

        if ((q1b == q1) && (len == 1)) {
            return _names[offset2 >> 4];
        }
        if (len == 0) { // empty slot; unlikely but avoid further lookups if so
            return null;
        }

        // tertiary lookup & spillovers best to offline
        return _findSecondary(offset, q1);
    }

    public String findName(int q1, int q2)
    {
        int hash = (q2 == 0) ? calcHash(q1) : calcHash(q1, q2);
        int offset = _calcOffset(hash);

        final int[] hashArea = _hash;

        int q1b = hashArea[offset];
        int len = hashArea[offset+3];

        if ((q1 == q1b) && (hashArea[offset+1] == q2) && (len == 2)) {
            return _names[offset >> 2];
        }
        if (len == 0) { // empty slot; unlikely but avoid further lookups if so
            return null;
        }
        // secondary?
        int offset2 = _hashSize + (offset>>1);

        q1b = hashArea[offset2];
        len = hashArea[offset2+3];

        if ((q1 == q1b) && (hashArea[offset2+1] == q2) && (len == 2)) {
            return _names[offset2 >> 2];
        }
        if (len == 0) { // empty slot? Short-circuit if no more spillovers
            return null;
        }
        return _findSecondary(offset, q1, q2);
    }

    public String findName(int q1, int q2, int q3)
    {
        int offset = _calcOffset(calcHash(q1, q2, q3));

        final int[] hashArea = _hash;

        int q1b = hashArea[offset];
        int len = hashArea[offset+3];
        
        if ((q1 == q1b) && (hashArea[offset+1] == q2) && (hashArea[offset+2] == q3) && (len == 3)) {
            return _names[offset >> 2];
        }
        if (len == 0) { // empty slot; unlikely but avoid further lookups if so
            return null;
        }
        // secondary?
        int offset2 = _hashSize + (offset>>1);

        q1b = hashArea[offset2];
        len = hashArea[offset2+3];

        if ((q1 == q1b) && (hashArea[offset2+1] == q2) && (hashArea[offset2+2] == q3) && (len == 3)) {
            return _names[offset2 >> 2];
        }
        if (len == 0) { // empty slot? Short-circuit if no more spillovers
            return null;
        }
        return _findSecondary(offset, q1, q2, q3);
    }

    public String findName(int[] q, int qlen)
    {
        /* This version differs significantly, because longer names do not fit within cell.
         * Rather, they contain hash in main slot, and offset+length to extension area
         * that contains actual quads.
         */
        if (qlen < 4) { // another sanity check
            if (qlen == 3) {
                return findName(q[0], q[1], q[2]);
            }
            return findName(q[0], (qlen < 2) ? 0 : q[1]);
        }
        final int hash = calcHash(calcHash(q, qlen));
        int offset = _calcOffset(hash);

        final int[] hashArea = _hash;

        int h = hashArea[offset];
        int len = hashArea[offset+3];
        
        if ((hash == h) && (len == qlen)) {
            // probable but not guaranteed: verify
            if (_verifyLongName(q, qlen, hashArea[offset+1])) {
                return _names[offset >> 2];
            }
        }
        if (len == 0) { // empty slot; unlikely but avoid further lookups if so
            return null;
        }
        // secondary?
        int offset2 = _hashSize + (offset>>1);

        h = hashArea[offset2];
        len = hashArea[offset2+3];

        if ((hash == h) && (len == qlen)) {
            if (_verifyLongName(q, qlen, hashArea[offset2+1])) {
                return _names[offset2 >> 2];
            }
        }
        if (len == 0) { // empty slot? Short-circuit if no more spillovers
            return null;
        }
        return _findSecondary(offset, hash, q, qlen);
    }
    
    private final int _calcOffset(int hash)
    {
        // NOTE: simple for initial impl, but we may want to interleave it a bit
        // in near future
        // So: first, hash into primary hash index
        int ix = hash & _hashMask;
        // keeping in mind we have 4 ints per entry
        return (ix << 2);
    }

    /*
    /**********************************************************
    /* Access from spill-over areas
    /**********************************************************
     */

    private String _findSecondary(int origOffset, int q1)
    {
        // so, first tertiary, 4 cells shared by N/16 primary slots
        int offset = _hashSize;
        offset += (offset >> 1); // to skip secondary area
        offset += (origOffset >> 4);

        final int[] hashArea = _hash;
        
        // then check up to 4 slots; don't worry about empty slots yet
        if ((q1 == hashArea[offset]) && (1 == hashArea[offset+3])) {
            return _names[offset >> 2];
        }
        offset += 4;
        if ((q1 == hashArea[offset]) && (1 == hashArea[offset+3])) {
            return _names[offset >> 2];
        }
        offset += 4;
        if ((q1 == hashArea[offset]) && (1 == hashArea[offset+3])) {
            return _names[offset >> 2];
        }
        offset += 4;
        int len = hashArea[offset+3];
        if ((q1 == hashArea[offset]) && (1 == len)) {
            return _names[offset >> 2];
        }
        // and only at this point see if last slot was occupied or not, to see whether to continue
        if (len != 0) {
            // shared spillover starts at 7/8 of the main hash area
            // (which is sized at 2 * _hashSize), so:
            offset = (_hashSize << 1) - (_hashSize >> 8);
            for (int i = 0, end = _spillOverCount; i < end; ++i, offset += 16) {
                if ((q1 == hashArea[offset]) && (1 == hashArea[offset+3])) {
                    return _names[offset >> 2];
                }
            }
        }
        return null;
    }

    private String _findSecondary(int origOffset, int q1, int q2)
    {
        int offset = _hashSize;
        offset += (offset >> 1);
        offset += (origOffset >> 4);

        final int[] hashArea = _hash;
        
        if ((q1 == hashArea[offset]) && (q2 == hashArea[offset+1]) && (2 == hashArea[offset+3])) {
            return _names[offset >> 2];
        }
        offset += 4;
        if ((q1 == hashArea[offset]) && (q2 == hashArea[offset+1]) && (2 == hashArea[offset+3])) {
            return _names[offset >> 2];
        }
        offset += 4;
        if ((q1 == hashArea[offset]) && (q2 == hashArea[offset+1]) && (2 == hashArea[offset+3])) {
            return _names[offset >> 2];
        }
        offset += 4;
        int len = hashArea[offset+3];
        if ((q1 == hashArea[offset]) && (q2 == hashArea[offset+1]) && (2 == len)) {
            return _names[offset >> 2];
        }
        // and only at this point see if last slot was occupied or not, to see whether to continue
        if (len != 0) {
            // shared spillover starts at 7/8 of the main hash area
            // (which is sized at 2 * _hashSize), so:
            offset = (_hashSize << 1) - (_hashSize >> 8);
            for (int i = 0, end = _spillOverCount; i < end; ++i, offset += 4) {
                if ((q1 == hashArea[offset]) && (q2 == hashArea[offset+1]) && (2 == hashArea[offset+3])) {
                    return _names[offset >> 2];
                }
            }
        }
        return null;
    }

    private String _findSecondary(int origOffset, int q1, int q2, int q3)
    {
        int offset = _hashSize;
        offset += (offset >> 1);
        offset += (origOffset >> 4);

        final int[] hashArea = _hash;
        
        if ((q1 == hashArea[offset]) && (q2 == hashArea[offset+1]) && (q3 == hashArea[offset+2]) && (3 == hashArea[offset+3])) {
            return _names[offset >> 2];
        }
        offset += 4;
        if ((q1 == hashArea[offset]) && (q2 == hashArea[offset+1]) && (q3 == hashArea[offset+2]) && (3 == hashArea[offset+3])) {
            return _names[offset >> 2];
        }
        offset += 4;
        if ((q1 == hashArea[offset]) && (q2 == hashArea[offset+1]) && (q3 == hashArea[offset+2]) && (3 == hashArea[offset+3])) {
            return _names[offset >> 2];
        }
        offset += 4;
        int len = hashArea[offset+3];
        if ((q1 == hashArea[offset]) && (q2 == hashArea[offset+1]) && (q3 == hashArea[offset+2]) && (3 == len)) {
            return _names[offset >> 2];
        }
        // and only at this point see if last slot was occupied or not, to see whether to continue
        if (len != 0) {
            // shared spillover starts at 7/8 of the main hash area
            // (which is sized at 2 * _hashSize), so:
            offset = (_hashSize << 1) - (_hashSize >> 8);
            for (int i = 0, end = _spillOverCount; i < end; ++i, offset += 4) {
                if ((q1 == hashArea[offset]) && (q2 == hashArea[offset+1]) && (q3 == hashArea[offset+2])
                        && (3 == hashArea[offset+3])) {
                    return _names[offset >> 2];
                }
            }
        }
        return null;
    }

    private String _findSecondary(int origOffset, int hash, int[] q, int qlen)
    {
        int offset = _hashSize;
        offset += (offset >> 1);
        offset += (origOffset >> 4);
        
        final int[] hashArea = _hash;
        
        if ((hash == hashArea[offset]) && (qlen == hashArea[offset+3])) {
            if (_verifyLongName(q, qlen, hashArea[offset+1])) {
                return _names[offset >> 2];
            }
        }
        offset += 4;
        if ((hash == hashArea[offset]) && (qlen == hashArea[offset+3])) {
            if (_verifyLongName(q, qlen, hashArea[offset+1])) {
                return _names[offset >> 2];
            }
        }
        offset += 4;
        if ((hash == hashArea[offset]) && (qlen == hashArea[offset+3])) {
            if (_verifyLongName(q, qlen, hashArea[offset+1])) {
                return _names[offset >> 2];
            }
        }
        offset += 4;
        int len = hashArea[offset+3];
        if ((hash == hashArea[offset]) && (qlen == len)) {
            if (_verifyLongName(q, qlen, hashArea[offset+1])) {
                return _names[offset >> 2];
            }
        }
        // and only at this point see if last slot was occupied or not, to see whether to continue
        if (len != 0) {
            // shared spillover starts at 7/8 of the main hash area
            // (which is sized at 2 * _hashSize), so:
            offset = (_hashSize << 1) - (_hashSize >> 8);
            for (int i = 0, end = _spillOverCount; i < end; ++i, offset += 4) {
                if ((hash == hashArea[offset]) && (3 == len)) {
                    if (_verifyLongName(q, qlen, hashArea[offset+1])) {
                        return _names[offset >> 2];
                    }
                }
            }
        }
        return null;
    }
    
    private boolean _verifyLongName(int[] q, int qlen, int spillOffset)
    {
        final int[] hashArea = _hash;
        // spillOffset assumed to be physical index right into quad string

        int ix = 0;
        do {
            if (q[ix++] != hashArea[spillOffset++]) {
                return false;
            }
        } while (ix < qlen);
        return true;
    }

    /*
    /**********************************************************
    /* API, mutators
    /**********************************************************
     */

    public String addName(String name, int q1, int q2)
    {
        if (_intern) {
            name = InternCache.instance.intern(name);
        }
        int hash = (q2 == 0) ? calcHash(q1) : calcHash(q1, q2);
//        return _addSymbol(hash, name);
        return null;
    }
    
    public String addName(String name, int[] q, int qlen)
    {
        if (_intern) {
            name = InternCache.instance.intern(name);
        }
        int hash;
        if (qlen < 4) {
            if (qlen == 1) {
                hash = calcHash(q[0]);
            } else if (qlen == 2) {
                hash = calcHash(q[0], q[1]);
            } else {
                hash = calcHash(q[0], q[1], q[2]);
            }
        } else {
            hash = calcHash(q, qlen);
        }
//        return _addSymbol(hash, name);
        return null;
    }
    
    /*
    /**********************************************************
    /* Hash calculation
    /**********************************************************
     */

    /* Note on hash calculation: we try to make it more difficult to
     * generate collisions automatically; part of this is to avoid
     * simple "multiply-add" algorithm (like JDK String.hashCode()),
     * and add bit of shifting. And other part is to make this
     * non-linear, at least for shorter symbols.
     */
    
    // JDK uses 31; other fine choices are 33 and 65599, let's use 33
    // as it seems to give fewest collisions for us
    // (see [http://www.cse.yorku.ca/~oz/hash.html] for details)
    private final static int MULT = 33;
    private final static int MULT2 = 65599;
    private final static int MULT3 = 31;
    
    public int calcHash(int q1)
    {
        int hash = q1 ^ _seed;
        hash += (hash >>> 15); // to xor hi- and low- 16-bits
        hash ^= (hash >>> 9); // as well as lowest 2 bytes
        return hash;
    }

    public int calcHash(int q1, int q2)
    {
        int hash = q1;
        hash ^= (hash >>> 15); // try mixing first and second byte pairs first
        hash += (q2 * MULT); // then add second quad
        hash ^= _seed;
        hash += (hash >>> 7); // and shuffle some more
        return hash;
    }

    public int calcHash(int q1, int q2, int q3)
    { // use same algorithm as multi-byte, tested to work well
        int hash = q1 ^ _seed;
        hash += (hash >>> 9);
        hash *= MULT;
        hash += q2;
        hash *= MULT2;
        hash += (hash >>> 15);
        hash ^= q3;
        hash += (hash >>> 17);

        hash += (hash >>> 15);
        hash ^= (hash << 9);

        return hash;
    }
    
    public int calcHash(int[] q, int qlen)
    {
        if (qlen < 4) {
            throw new IllegalArgumentException();
        }

        /* And then change handling again for "multi-quad" case; mostly
         * to make calculation of collisions less fun. For example,
         * add seed bit later in the game, and switch plus/xor around,
         * use different shift lengths.
         */
        int hash = q[0] ^ _seed;
        hash += (hash >>> 9);
        hash *= MULT;
        hash += q[1];
        hash *= MULT2;
        hash += (hash >>> 15);
        hash ^= q[2];
        hash += (hash >>> 17);
        
        for (int i = 3; i < qlen; ++i) {
            hash = (hash * MULT3) ^ q[i];
            // for longer entries, mess a bit in-between too
            hash += (hash >>> 3);
            hash ^= (hash << 7);
        }
        // and finally shuffle some more once done
        hash += (hash >>> 15); // to get high-order bits to mix more
        hash ^= (hash << 9); // as well as lowest 2 bytes
        return hash;
    }
   
    /*
    /**********************************************************
    /* Helper classes
    /**********************************************************
     */

    /**
     * Immutable value class used for sharing information as efficiently
     * as possible, by only require synchronization of reference manipulation
     * but not access to contents.
     * 
     * @since 2.1
     */
    private final static class TableInfo
    {
        public final int size;
        public final int count;
        public final int[] mainHash;
        public final String[] names;
        public final int collCount;
        public final int longestCollisionList;

        public TableInfo(int size, int count, int[] mainHash, String[] names,
                int collCount, int longestCollisionList)
        {
            this.size = size;
            this.count = count;
            this.mainHash = mainHash;
            this.names = names;
            this.collCount = collCount;
            this.longestCollisionList = longestCollisionList;
        }

        public TableInfo(ByteQuadsCanonicalizer src)
        {
            size = src._hashSize;
            count = src._count;
            mainHash = src._hash;
            names = src._names;
            collCount = src._collCount;
            longestCollisionList = src._longestCollisionList;
        }

        public static TableInfo createInitial(int sz) {
            return new TableInfo(sz, // hashSize
                    0, // count
                    new int[sz * 2], // mainHash
                    new String[sz], // mainNames
                    0, // collCount,
                    0 // longestCollisionList
            );
        }
    }
}
