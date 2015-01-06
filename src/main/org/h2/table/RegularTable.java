/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.table;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import org.h2.api.DatabaseEventListener;
import org.h2.api.ErrorCode;
import org.h2.command.ddl.Analyze;
import org.h2.command.ddl.CreateTableData;
import org.h2.constraint.Constraint;
import org.h2.constraint.ConstraintReferential;
import org.h2.engine.Constants;
import org.h2.engine.DbObject;
import org.h2.engine.Session;
import org.h2.engine.SysProperties;
import org.h2.index.Cursor;
import org.h2.index.HashIndex;
import org.h2.index.Index;
import org.h2.index.IndexType;
import org.h2.index.MultiVersionIndex;
import org.h2.index.NonUniqueHashIndex;
import org.h2.index.PageBtreeIndex;
import org.h2.index.PageDataIndex;
import org.h2.index.PageDelegateIndex;
import org.h2.index.ScanIndex;
import org.h2.index.SpatialTreeIndex;
import org.h2.index.TreeIndex;
import org.h2.message.DbException;
import org.h2.message.Trace;
import org.h2.result.Row;
import org.h2.result.SortOrder;
import org.h2.schema.SchemaObject;
import org.h2.util.MathUtils;
import org.h2.util.New;
import org.h2.value.CompareMode;
import org.h2.value.DataType;
import org.h2.value.Value;

/**
 * Most tables are an instance of this class. For this table, the data is stored
 * in the database. The actual data is not kept here, instead it is kept in the
 * indexes. There is at least one index, the scan index.
 */
public class RegularTable extends TableBase {

    private Index scanIndex; //要么是PageDataIndex，要么是ScanIndex
    private long rowCount;
    private volatile Session lockExclusiveSession;
    private HashSet<Session> lockSharedSessions = New.hashSet();

    /**
     * The queue of sessions waiting to lock the table. It is a FIFO queue to
     * prevent starvation, since Java's synchronized locking is biased.
     */
    private final ArrayDeque<Session> waitingSessions = new ArrayDeque<Session>();
    private final Trace traceLock;
    private final ArrayList<Index> indexes = New.arrayList();
    private long lastModificationId; //在addRow、commit、removeRow、truncate时改变
    private boolean containsLargeObject;
    private final PageDataIndex mainIndex; //这个字段用来选主索引列时有用
    private int changesSinceAnalyze;
    private int nextAnalyze;
    private Column rowIdColumn;

    public RegularTable(CreateTableData data) {
        super(data);
        nextAnalyze = database.getSettings().analyzeAuto;
        this.isHidden = data.isHidden;
        for (Column col : getColumns()) {
            if (DataType.isLargeObject(col.getType())) {
                containsLargeObject = true;
            }
        }
        //如果database.isPersistent()是false，说明是内存数据库
        //如果data.persistData是false，说明是内存临时表
        //当是内存数据库时，不管data.persistData是false还是true都使用ScanIndex(也就是内存索引)
        if (data.persistData && database.isPersistent()) {
            mainIndex = new PageDataIndex(this, data.id,
                    IndexColumn.wrap(getColumns()),
                    IndexType.createScan(data.persistData),
                    data.create, data.session);
            scanIndex = mainIndex;
        } else {
            mainIndex = null;
            scanIndex = new ScanIndex(this, data.id,
                    IndexColumn.wrap(getColumns()), IndexType.createScan(data.persistData));
        }
        indexes.add(scanIndex);
        traceLock = database.getTrace(Trace.LOCK); //在起用debug时，其实就是用来输出debug日志，在lock/unlock时记录debug日志
    }

    @Override
    public void close(Session session) {
        for (Index index : indexes) {
            index.close(session);
        }
    }

    @Override
    public Row getRow(Session session, long key) { //从辅助索引那里过来的，索引那里记录了主表记录的key，按key获取主表的完整记录
        return scanIndex.getRow(session, key);
    }

    @Override
    public void addRow(Session session, Row row) {
        lastModificationId = database.getNextModificationDataId();
        if (database.isMultiVersion()) {
            row.setSessionId(session.getId());
        }
        int i = 0;
        try {
        	//truncate、removeRow一样，都是从最后一个索引开始, 而addRow、commit是从第一个开始
            for (int size = indexes.size(); i < size; i++) {
                Index index = indexes.get(i);
                index.add(session, row);
                checkRowCount(session, index, 1);
            }
            rowCount++;
        } catch (Throwable e) {
            try {
            	//因为第i个index抛异常了，所以就没必要从i开始了，直接--i
                while (--i >= 0) {
                    Index index = indexes.get(i);
                    index.remove(session, row);
                    checkRowCount(session, index, 0); //rowCount+0等于index.getRowCount
                }
            } catch (DbException e2) {
                // this could happen, for example on failure in the storage
                // but if that is not the case it means there is something wrong
                // with the database
                trace.error(e2, "could not undo operation");
                throw e2;
            }
            DbException de = DbException.convert(e);
            if (de.getErrorCode() == ErrorCode.DUPLICATE_KEY_1) {
                for (int j = 0; j < indexes.size(); j++) {
                    Index index = indexes.get(j);
                    if (index.getIndexType().isUnique() && index instanceof MultiVersionIndex) {
                        MultiVersionIndex mv = (MultiVersionIndex) index;
                        if (mv.isUncommittedFromOtherSession(session, row)) {
                            throw DbException.get(
                                    ErrorCode.CONCURRENT_UPDATE_1, index.getName());
                        }
                    }
                }
            }
            throw de;
        }
        analyzeIfRequired(session);
    }

    @Override
    public void commit(short operation, Row row) {
        lastModificationId = database.getNextModificationDataId();
        //truncate、removeRow一样，都是从最后一个索引开始, 而addRow、commit是从第一个开始
        for (int i = 0, size = indexes.size(); i < size; i++) {
            Index index = indexes.get(i);
            index.commit(operation, row);
        }
    }

    private void checkRowCount(Session session, Index index, int offset) {
        if (SysProperties.CHECK && !database.isMultiVersion()) {
            if (!(index instanceof PageDelegateIndex)) {
                long rc = index.getRowCount(session);
                if (rc != rowCount + offset) {
                    DbException.throwInternalError(
                            "rowCount expected " + (rowCount + offset) +
                            " got " + rc + " " + getName() + "." + index.getName());
                }
            }
        }
    }

    @Override
    public Index getScanIndex(Session session) {
        return indexes.get(0); //ScanIndex总是在最前面
    }

    @Override
    public Index getUniqueIndex() {
        for (Index idx : indexes) {
            if (idx.getIndexType().isUnique()) {
                return idx;
            }
        }
        return null;
    }

    @Override
    public ArrayList<Index> getIndexes() {
        return indexes;
    }

    @Override
    public Index addIndex(Session session, String indexName, int indexId,
            IndexColumn[] cols, IndexType indexType, boolean create,
            String indexComment) {
        if (indexType.isPrimaryKey()) {
            for (IndexColumn c : cols) {
                Column column = c.column;
                if (column.isNullable()) {
                    throw DbException.get(
                            ErrorCode.COLUMN_MUST_NOT_BE_NULLABLE_1, column.getName());
                }
                column.setPrimaryKey(true);
            }
        }
        //如果是LOCAL TEMPORARY表，则只放到session的LocalTempTableIndex中，否则加到database的meta表
        boolean isSessionTemporary = isTemporary() && !isGlobalTemporary();
        if (!isSessionTemporary) {
            database.lockMeta(session);
        }
        Index index;
        //PrimaryKey索引，并且只有一个字段，并且此字段是byte、short、int、long类型才能作为mainIndexColumn
        //且最初的mainIndex还没有加入记录
        if (isPersistIndexes() && indexType.isPersistent()) {
            int mainIndexColumn;
            if (database.isStarting() &&
                    database.getPageStore().getRootPageId(indexId) != 0) {
                mainIndexColumn = -1;
            } else if (!database.isStarting() && mainIndex.getRowCount(session) != 0) {
                mainIndexColumn = -1;
            } else {
                mainIndexColumn = getMainIndexColumn(indexType, cols);
            }
            if (mainIndexColumn != -1) {
                mainIndex.setMainIndexColumn(mainIndexColumn);
                //PageDelegateIndex在增加行时什么都不做，
                //满足这个条件的索引: "PrimaryKey索引，并且只有一个字段，并且此字段是byte、short、int、long类型"
                //实际上在增加行时只有最初的PageDataIndex起作用，
                //PageDelegateIndex只在查询时有作用
                index = new PageDelegateIndex(this, indexId, indexName, indexType, mainIndex, create, session);
            } else if (indexType.isSpatial()) {
                index = new SpatialTreeIndex(this, indexId, indexName, cols,
                        indexType, true, create, session);
            } else {
                index = new PageBtreeIndex(this, indexId, indexName, cols,
                        indexType, create, session);
            }
        } else {
        	//hash索引最多只有一列
            if (indexType.isHash()) {
                if (cols.length != 1) {
                    throw DbException.getUnsupportedException(
                            "hash indexes may index only one column");
                }

                if (indexType.isUnique()) {
                    index = new HashIndex(this, indexId, indexName, cols,
                            indexType);
                } else {
                    index = new NonUniqueHashIndex(this, indexId, indexName,
                            cols, indexType);
                }
            } else if (indexType.isSpatial()) {
                index = new SpatialTreeIndex(this, indexId, indexName, cols,
                        indexType, false, true, session);
            } else {
                index = new TreeIndex(this, indexId, indexName, cols, indexType);
            }
        }
        if (database.isMultiVersion()) {
            index = new MultiVersionIndex(index, this);
        }
        //从ScanIndex中读出原始记录，新建或重建索引
        if (index.needRebuild() && rowCount > 0) {
            try {
                Index scan = getScanIndex(session);
                long remaining = scan.getRowCount(session);
                long total = remaining;
                Cursor cursor = scan.find(session, null, null);
                long i = 0;
                int bufferSize = (int) Math.min(rowCount, database.getMaxMemoryRows());
                ArrayList<Row> buffer = New.arrayList(bufferSize);
                String n = getName() + ":" + index.getName();
                int t = MathUtils.convertLongToInt(total);
                while (cursor.next()) {
                    database.setProgress(DatabaseEventListener.STATE_CREATE_INDEX, n,
                            MathUtils.convertLongToInt(i++), t);
                    Row row = cursor.get();
                    buffer.add(row);
                    if (buffer.size() >= bufferSize) {
                        addRowsToIndex(session, buffer, index);
                    }
                    remaining--;
                }
                addRowsToIndex(session, buffer, index);
                if (SysProperties.CHECK && remaining != 0) {
                    DbException.throwInternalError("rowcount remaining=" +
                            remaining + " " + getName());
                }
            } catch (DbException e) {
                getSchema().freeUniqueName(indexName);
                try {
                    index.remove(session);
                } catch (DbException e2) {
                    // this could happen, for example on failure in the storage
                    // but if that is not the case it means
                    // there is something wrong with the database
                    trace.error(e2, "could not remove index");
                    throw e2;
                }
                throw e;
            }
        }
        index.setTemporary(isTemporary());
        if (index.getCreateSQL() != null) {
            index.setComment(indexComment);
            if (isSessionTemporary) {
                session.addLocalTempTableIndex(index);
            } else {
                database.addSchemaObject(session, index);
            }
        }
        indexes.add(index);
        setModified();
        return index;
    }

    private int getMainIndexColumn(IndexType indexType, IndexColumn[] cols) {
        if (mainIndex.getMainIndexColumn() != -1) {
            return -1;
        }
        if (!indexType.isPrimaryKey() || cols.length != 1) {
            return -1;
        }
        IndexColumn first = cols[0];
        if (first.sortType != SortOrder.ASCENDING) {
            return -1;
        }
        switch(first.column.getType()) {
        case Value.BYTE:
        case Value.SHORT:
        case Value.INT:
        case Value.LONG:
            break;
        default:
            return -1;
        }
        return first.column.getColumnId();
    }

    @Override
    public boolean canGetRowCount() {
        return true;
    }

    private static void addRowsToIndex(Session session, ArrayList<Row> list,
            Index index) {
        final Index idx = index;
        Collections.sort(list, new Comparator<Row>() {
            @Override
            public int compare(Row r1, Row r2) {
                return idx.compareRows(r1, r2);
            }
        });
        for (Row row : list) {
            index.add(session, row);
        }
        list.clear();
    }

    @Override
    public boolean canDrop() {
        return true;
    }

    @Override
    public long getRowCount(Session session) {
        if (database.isMultiVersion()) {
            return getScanIndex(session).getRowCount(session);
        }
        return rowCount;
    }

    @Override
    public void removeRow(Session session, Row row) {
        if (database.isMultiVersion()) {
            if (row.isDeleted()) { //用org.h2.test.rowlock.TestRowLocks可测试这里
                throw DbException.get(ErrorCode.CONCURRENT_UPDATE_1, getName());
            }
            int old = row.getSessionId();
            int newId = session.getId();
            if (old == 0) {
                row.setSessionId(newId);
            } else if (old != newId) {
                throw DbException.get(ErrorCode.CONCURRENT_UPDATE_1, getName());
            }
        }
        lastModificationId = database.getNextModificationDataId();
        int i = indexes.size() - 1;
        try {
            for (; i >= 0; i--) { //truncate、removeRow一样，都是从最后一个索引开始, 而addRow、commit是从第一个开始
                Index index = indexes.get(i);
                index.remove(session, row);
                checkRowCount(session, index, -1);
            }
            rowCount--;
        } catch (Throwable e) {
            try {
                while (++i < indexes.size()) {
                    Index index = indexes.get(i);
                    index.add(session, row);
                    checkRowCount(session, index, 0);
                }
            } catch (DbException e2) {
                // this could happen, for example on failure in the storage
                // but if that is not the case it means there is something wrong
                // with the database
                trace.error(e2, "could not undo operation");
                throw e2;
            }
            throw DbException.convert(e);
        }
        analyzeIfRequired(session);
    }

    @Override
    public void truncate(Session session) {
        lastModificationId = database.getNextModificationDataId();
        //truncate、removeRow一样，都是从最后一个索引开始, 而addRow、commit是从第一个开始
        for (int i = indexes.size() - 1; i >= 0; i--) {
            Index index = indexes.get(i);
            index.truncate(session);
        }
        rowCount = 0;
        changesSinceAnalyze = 0;
    }
    
    //默认插入2000行时，为每一字段调用SELECTIVITY聚合函数，然后修改字段的SELECTIVITY值，重新更新一下此表的meta信息
    private void analyzeIfRequired(Session session) {
        if (nextAnalyze == 0 || nextAnalyze > changesSinceAnalyze++) {
            return;
        }
        changesSinceAnalyze = 0;
        int n = 2 * nextAnalyze; //每算完一次就翻倍，比如第一次是2000行算一次，下次是4000，再下次是8000...
        if (n > 0) {
            nextAnalyze = n;
        }
        int rows = session.getDatabase().getSettings().analyzeSample / 10; //抽样是1万行/10

        Analyze.analyzeTable(session, this, rows, false);
    }

    @Override
    public boolean isLockedExclusivelyBy(Session session) {
        return lockExclusiveSession == session;
    }
    
    //直到事务commit或rollback时才解琐，见org.h2.engine.Session.unlockAll()
    //Select没有锁表
    //比如DDL相关的SQL通常把force设为true，此时不管MVCC，META表都是把force设为true，
    //Select的isForUpdate变种在非MVCC下也把force设为true，
    //Insert、Update之类的才设为false
    @Override
    public boolean lock(Session session, boolean exclusive,
            boolean forceLockEvenInMvcc) { //琐粒度太大，每insert一行都琐表
        int lockMode = database.getLockMode();
        if (lockMode == Constants.LOCK_MODE_OFF) { //禁用锁
            return lockExclusiveSession != null;
        }
        if (!forceLockEvenInMvcc && database.isMultiVersion()) { //如果使用了MVCC，并且不是强制的，则 不使用排它琐
            // MVCC: update, delete, and insert use a shared lock.
            // Select doesn't lock except when using FOR UPDATE
            if (exclusive) {
                exclusive = false; //禁用排它琐
            } else {
                if (lockExclusiveSession == null) {
                    return false;
                }
            }
        }
        if (lockExclusiveSession == session) {
            return true;
        }
        synchronized (database) {
            if (lockExclusiveSession == session) {
                return true;
            }
            if (!exclusive && lockSharedSessions.contains(session)) {
                return true;
            }
            session.setWaitForLock(this, Thread.currentThread());
            waitingSessions.addLast(session);
            try {
                doLock1(session, lockMode, exclusive);
            } finally {
                session.setWaitForLock(null, null);
                waitingSessions.remove(session);
            }
        }
        return false;
    }

    private void doLock1(Session session, int lockMode, boolean exclusive) {
        traceLock(session, exclusive, "requesting for");
        // don't get the current time unless necessary
        long max = 0;
        boolean checkDeadlock = false;
        while (true) {
//<<<<<<< HEAD
//            if (lockExclusive == session) {
//                return;
//            }
//            if (exclusive) {
//                if (lockExclusive == null) {
//                    if (lockShared.isEmpty()) {
//                        traceLock(session, exclusive, "added for");
//                        session.addLock(this);
//                        lockExclusive = session;
//                        return;
//                    //如果前面有一个读锁，并且是相同的session，那么insert之类的操作不须等待
//                    } else if (lockShared.size() == 1 && lockShared.contains(session)) {
//                        traceLock(session, exclusive, "add (upgraded) for ");
//                        lockExclusive = session;
//                        return;
//                    }
//                }
//            } else {
//            	//如果lockExclusive不为null，说明前面有一个排它锁，不管当前操作是查询还是更新，都必须等待，
//            	//如果lockExclusive为null，那么当前操作可顺利进行
//                if (lockExclusive == null) {
//                    if (lockMode == Constants.LOCK_MODE_READ_COMMITTED) {
//                        if (!database.isMultiThreaded() && !database.isMultiVersion()) {
//                            // READ_COMMITTED: a read lock is acquired,
//                            // but released immediately after the operation
//                            // is complete.
//                            // When allowing only one thread, no lock is
//                            // required.
//                            // Row level locks work like read committed.
//                            return;
//                        }
//                    }
//                    if (!lockShared.contains(session)) {
//                        traceLock(session, exclusive, "ok");
//                        session.addLock(this);
//                        lockShared.add(session);
//                    }
//=======
            // if I'm the next one in the queue
            if (waitingSessions.getFirst() == session) {
                if (doLock2(session, lockMode, exclusive)) {

                    return;
                }
            }
            if (checkDeadlock) {
                ArrayList<Session> sessions = checkDeadlock(session, null, null);
                if (sessions != null) {
                    throw DbException.get(ErrorCode.DEADLOCK_1,
                            getDeadlockDetails(sessions, exclusive));
                }
            } else {
                // check for deadlocks from now on
                checkDeadlock = true;
            }
            long now = System.currentTimeMillis();
            if (max == 0) {
                // try at least one more time
                max = now + session.getLockTimeout();
            } else if (now >= max) {
                traceLock(session, exclusive, "timeout after " + session.getLockTimeout());
                throw DbException.get(ErrorCode.LOCK_TIMEOUT_1, getName());
            }
            try {
                traceLock(session, exclusive, "waiting for");
                if (database.getLockMode() == Constants.LOCK_MODE_TABLE_GC) {
                    for (int i = 0; i < 20; i++) {
                        long free = Runtime.getRuntime().freeMemory();
                        System.gc();
                        long free2 = Runtime.getRuntime().freeMemory();
                        if (free == free2) {
                            break;
                        }
                    }
                }
                // don't wait too long so that deadlocks are detected early
                long sleep = Math.min(Constants.DEADLOCK_CHECK, max - now);
                if (sleep == 0) {
                    sleep = 1;
                }
                database.wait(sleep);
            } catch (InterruptedException e) {
                // ignore
            }
        }
    }

    private boolean doLock2(Session session, int lockMode, boolean exclusive) {
        if (exclusive) {
            if (lockExclusiveSession == null) {
                if (lockSharedSessions.isEmpty()) {
                    traceLock(session, exclusive, "added for");
                    session.addLock(this);
                    lockExclusiveSession = session;
                    return true;
                } else if (lockSharedSessions.size() == 1 &&
                        lockSharedSessions.contains(session)) {
                    traceLock(session, exclusive, "add (upgraded) for ");
                    lockExclusiveSession = session;
                    return true;
                }
            }
        } else {
            if (lockExclusiveSession == null) {
                if (lockMode == Constants.LOCK_MODE_READ_COMMITTED) {
                    if (!database.isMultiThreaded() && !database.isMultiVersion()) {
                        // READ_COMMITTED: a read lock is acquired,
                        // but released immediately after the operation
                        // is complete.
                        // When allowing only one thread, no lock is
                        // required.
                        // Row level locks work like read committed.
                        return true;
                    }
                }
                if (!lockSharedSessions.contains(session)) {
                    traceLock(session, exclusive, "ok");
                    session.addLock(this);
                    lockSharedSessions.add(session);
                }
                return true;
            }
        }
        return false;
    }
    private static String getDeadlockDetails(ArrayList<Session> sessions, boolean exclusive) {
        // We add the thread details here to make it easier for customers to
        // match up these error messages with their own logs.
        StringBuilder buff = new StringBuilder();
        for (Session s : sessions) {
            Table lock = s.getWaitForLock();
            Thread thread = s.getWaitForLockThread();
            buff.append("\nSession ").
                append(s.toString()).
                append(" on thread ").
                append(thread.getName()).
                append(" is waiting to lock ").
                append(lock.toString()).
                append(exclusive ? " (exclusive)" : " (shared)").
                append(" while locking ");
            int i = 0;
            for (Table t : s.getLocks()) {
                if (i++ > 0) {
                    buff.append(", ");
                }
                buff.append(t.toString());
                if (t instanceof RegularTable) {
                    if (((RegularTable) t).lockExclusiveSession == s) {
                        buff.append(" (exclusive)");
                    } else {
                        buff.append(" (shared)");
                    }
                }
            }
            buff.append('.');
        }
        return buff.toString();
    }
    
    //参见org.h2.test.db.TestDeadlock类(此例使用内存数据库的方式测试)
    
	//    #2 (user: SA) 占用 PUBLIC.TEST_A
	//    #3 (user: SA) 占用 PUBLIC.TEST_B
	//    #4 (user: SA) 占用 PUBLIC.TEST_C
	//
	//    #2 (user: SA) 想要 PUBLIC.TEST_B，但是PUBLIC.TEST_B已被#3 (user: SA) 占用
	//
	//    #3 (user: SA) 想要 PUBLIC.TEST_C，但是PUBLIC.TEST_C已被#4 (user: SA) 占用
	//
	//    #4 (user: SA) 想要 PUBLIC.TEST_A，但是PUBLIC.TEST_A已被#2 (user: SA) 占用
	//
	//    Session #3 (user: SA) is waiting to lock PUBLIC.TEST_C while locking PUBLIC.TEST_B (exclusive).
	//    Session #2 (user: SA) is waiting to lock PUBLIC.TEST_B while locking PUBLIC.TEST_A (exclusive).
	//    Session #4 (user: SA) is waiting to lock PUBLIC.TEST_A while locking PUBLIC.TEST_C (exclusive).
    @Override
    public ArrayList<Session> checkDeadlock(Session session, Session clash,
            Set<Session> visited) {
        // only one deadlock check at any given time
        synchronized (RegularTable.class) {
            if (clash == null) {
                // verification is started
                clash = session;
                visited = New.hashSet();
            } else if (clash == session) {
                // we found a circle where this session is involved
                return New.arrayList();
            } else if (visited.contains(session)) {
                // we have already checked this session.
                // there is a circle, but the sessions in the circle need to
                // find it out themselves
                return null;
            }
            visited.add(session);
            ArrayList<Session> error = null;
            for (Session s : lockSharedSessions) {
                if (s == session) {
                    // it doesn't matter if we have locked the object already
                    continue;
                }
                Table t = s.getWaitForLock();
                if (t != null) {
                    error = t.checkDeadlock(s, clash, visited);
                    if (error != null) {
                        error.add(session);
                        break;
                    }
                }
            }
            if (error == null && lockExclusiveSession != null) {
                Table t = lockExclusiveSession.getWaitForLock();
                if (t != null) {
                    error = t.checkDeadlock(lockExclusiveSession, clash, visited);
                    if (error != null) {
                        error.add(session);
                    }
                }
            }
            return error;
        }
    }

    private void traceLock(Session session, boolean exclusive, String s) {
        if (traceLock.isDebugEnabled()) {
            traceLock.debug("{0} {1} {2} {3}", session.getId(),
                    exclusive ? "exclusive write lock" : "shared read lock", s, getName());
        }
    }

    @Override
    public boolean isLockedExclusively() {
        return lockExclusiveSession != null;
    }

    @Override
    public void unlock(Session s) {
        if (database != null) {
            traceLock(s, lockExclusiveSession == s, "unlock");
            if (lockExclusiveSession == s) {
                lockExclusiveSession = null;
            }
            if (lockSharedSessions.size() > 0) {
                lockSharedSessions.remove(s);
            }
            synchronized (database) {
                if (!waitingSessions.isEmpty()) {
                    database.notifyAll();
                }
            }
        }
    }

    /**
     * Create a row from the values.
     *
     * @param data the value list
     * @return the row
     */
    public static Row createRow(Value[] data) {
        return new Row(data, Row.MEMORY_CALCULATE);
    }

    /**
     * Set the row count of this table.
     *
     * @param count the row count
     */
    public void setRowCount(long count) {
        this.rowCount = count;
    }

    @Override
    public void removeChildrenAndResources(Session session) {
        if (containsLargeObject) {
            // unfortunately, the data is gone on rollback
            truncate(session);
            database.getLobStorage().removeAllForTable(getId());
            database.lockMeta(session);
        }
        //里面删除约束时会把属于约束的索引也删了，此时indexes会变化
        //在org.h2.index.BaseIndex.removeChildrenAndResources(Session)中删除
        super.removeChildrenAndResources(session);
        // go backwards because database.removeIndex will call table.removeIndex
        while (indexes.size() > 1) {
            Index index = indexes.get(1);
            if (index.getName() != null) {
                database.removeSchemaObject(session, index);
            }
            // needed for session temporary indexes
            indexes.remove(index);
        }
        if (SysProperties.CHECK) {
            for (SchemaObject obj : database.getAllSchemaObjects(DbObject.INDEX)) {
                Index index = (Index) obj;
                if (index.getTable() == this) {
                    DbException.throwInternalError("index not dropped: " + index.getName());
                }
            }
        }
        scanIndex.remove(session);
        database.removeMeta(session, getId());
        scanIndex = null;
        lockExclusiveSession = null;
        lockSharedSessions = null;
        invalidate();
    }

    @Override
    public String toString() { //模式名.表名
        return getSQL();
    }

    @Override
    public void checkRename() { //允许重命名
        // ok
    }

    @Override
    public void checkSupportAlter() { //允许使用alter命令
        // ok
    }

    @Override
    public boolean canTruncate() {
		// 例如这样是不行的:
		// stmt.executeUpdate("create table IF NOT EXISTS RegularTableTest1(id int,  primary key(id))");
		// stmt.executeUpdate("create table IF NOT EXISTS RegularTableTest2(id int,"
		// +
		// " FOREIGN KEY(id) REFERENCES RegularTableTest1(id))");
		//
		// stmt.executeUpdate("TRUNCATE TABLE RegularTableTest1");
    	//因为RegularTableTest2表引用了RegularTableTest1
        if (getCheckForeignKeyConstraints() && database.getReferentialIntegrity()) {
            ArrayList<Constraint> constraints = getConstraints();
            if (constraints != null) {
                for (int i = 0, size = constraints.size(); i < size; i++) {
                    Constraint c = constraints.get(i);
                    if (!(c.getConstraintType().equals(Constraint.REFERENTIAL))) {
                        continue;
                    }
                    ConstraintReferential ref = (ConstraintReferential) c;
                    //this是RegularTableTest1
                    if (ref.getRefTable() == this) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Override
    public String getTableType() {
        return Table.TABLE;
    }

    @Override
    public long getMaxDataModificationId() {
        return lastModificationId;
    }

    public boolean getContainsLargeObject() {
        return containsLargeObject;
    }

    @Override
    public long getRowCountApproximation() {
        return scanIndex.getRowCountApproximation(); //ScanIndex和PageDataIndex都是返回rowCount
    }

    @Override
    public long getDiskSpaceUsed() { //用于DISK_SPACE_USED函数
    	//如果是ScanIndex，因为ScanIndex只是个内存索引，所以返回0，
    	//如果么是PageDataIndex，那么就是leaf节点个数*pageSize。
        return scanIndex.getDiskSpaceUsed();
    }

    public void setCompareMode(CompareMode compareMode) {
        this.compareMode = compareMode;
    }

    @Override
    public boolean isDeterministic() {
        return true;
    }

    @Override
    public Column getRowIdColumn() { //ROWID伪列，columnId是-1
        if (rowIdColumn == null) {
            rowIdColumn = new Column(Column.ROWID, Value.LONG);
            rowIdColumn.setTable(this, -1);
        }
        return rowIdColumn;
    }

}
