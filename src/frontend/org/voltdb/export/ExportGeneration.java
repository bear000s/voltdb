/* This file is part of VoltDB.
 * Copyright (C) 2008-2012 VoltDB Inc.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.voltdb.export;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.zookeeper_voltpatches.AsyncCallback;
import org.apache.zookeeper_voltpatches.CreateMode;
import org.apache.zookeeper_voltpatches.KeeperException;
import org.apache.zookeeper_voltpatches.WatchedEvent;
import org.apache.zookeeper_voltpatches.Watcher;
import org.apache.zookeeper_voltpatches.ZooDefs.Ids;
import org.apache.zookeeper_voltpatches.ZooKeeper;
import org.voltcore.logging.VoltLogger;
import org.voltcore.messaging.BinaryPayloadMessage;
import org.voltcore.messaging.HostMessenger;
import org.voltcore.messaging.Mailbox;
import org.voltcore.messaging.VoltMessage;
import org.voltcore.utils.CoreUtils;
import org.voltcore.utils.DBBPool;
import org.voltcore.utils.Pair;
import org.voltcore.zk.ZKUtil;
import org.voltdb.CatalogContext;
import org.voltdb.VoltDB;
import org.voltdb.VoltZK;
import org.voltdb.catalog.Connector;
import org.voltdb.catalog.ConnectorTableInfo;
import org.voltdb.catalog.Table;
import org.voltdb.dtxn.SiteTracker;
import org.voltdb.iv2.TxnEgo;
import org.voltdb.messaging.LocalMailbox;
import org.voltdb.utils.VoltFile;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

/**
 * Export data from a single catalog version and database instance.
 *
 */
public class ExportGeneration {
    /**
     * Processors also log using this facility.
     */
    private static final VoltLogger exportLog = new VoltLogger("EXPORT");

    public final Long m_timestamp;
    public final File m_directory;

    private String m_zkPath;

    /**
     * Data sources, one per table per site, provide the interface to
     * poll() and ack() Export data from the execution engines. Data sources
     * are configured by the Export manager at initialization time.
     * partitionid : <tableid : datasource>.
     */
    public final HashMap<Integer, HashMap<String, ExportDataSource>> m_dataSourcesByPartition =
        new HashMap<Integer, HashMap<String, ExportDataSource>>();

    private int m_numSources = 0;
    private final AtomicInteger m_drainedSources = new AtomicInteger(0);

    private final Runnable m_onAllSourcesDrained;

    private final Runnable m_onSourceDrained = new Runnable() {
        @Override
        public void run() {
            int numSourcesDrained = m_drainedSources.incrementAndGet();
            exportLog.info("Drained source in generation " + m_timestamp + " with " + numSourcesDrained + " of " + m_numSources + " drained");
            if (numSourcesDrained == m_numSources) {
                m_onAllSourcesDrained.run();
            }
        }
    };

    private Mailbox m_mbox;

    private ZooKeeper m_zk;

    private static final ListeningExecutorService m_childUpdatingThread =
            CoreUtils.getListeningExecutorService("Export ZK Watcher", 1);
    private volatile ImmutableMap<Integer, ImmutableList<Long>> m_ackableMailboxes =
            ImmutableMap.<Integer, ImmutableList<Long>>builder().build();

    /**
     * Constructor to create a new generation of export data
     * @param exportOverflowDirectory
     * @throws IOException
     */
    public ExportGeneration(long txnId, Runnable onAllSourcesDrained, File exportOverflowDirectory) throws IOException {
        m_onAllSourcesDrained = onAllSourcesDrained;
        m_timestamp = txnId;
        m_directory = new File(exportOverflowDirectory, Long.toString(txnId) );
        if (!m_directory.mkdir()) {
            throw new IOException("Could not create " + m_directory);
        }
        exportLog.info("Creating new export generation " + m_timestamp);
    }

    /**
     * Constructor to create a generation based on one that has been persisted to disk
     * @param generationDirectory
     * @param generationTimestamp
     * @throws IOException
     */
    public ExportGeneration(
            Runnable onAllSourcesDrained,
            File generationDirectory,
            long generationTimestamp) throws IOException {
        m_onAllSourcesDrained = onAllSourcesDrained;
        m_timestamp = generationTimestamp;
        m_directory = generationDirectory;
        exportLog.info("Restoring export generation " + generationTimestamp);
    }

    void initializeGenerationFromDisk(final Connector conn, HostMessenger messenger) {
        m_zk = messenger.getZK();

        Set<Integer> partitions = new HashSet<Integer>();

        /*
         * Find all the advertisements. Once one is found, extract the nonce
         * and check for any data files related to the advertisement. If no data files
         * exist ignore the advertisement.
         */
        for (File f : m_directory.listFiles()) {
            if (f.getName().endsWith(".ad")) {
                boolean haveDataFiles = false;
                String nonce = f.getName().substring(0, f.getName().length() - 3);
                for (File dataFile : m_directory.listFiles()) {
                    if (dataFile.getName().startsWith(nonce) && !dataFile.getName().equals(f.getName())) {
                        haveDataFiles = true;
                        break;
                    }
                }

                if (haveDataFiles) {
                    try {
                        addDataSource(f, partitions);
                    } catch (IOException e) {
                        VoltDB.crashLocalVoltDB(e.getMessage(), true, e);
                    }
                } else {
                    //Delete ads that have no data
                    f.delete();
                }
            }
        }
        createAndRegisterAckMailboxes(partitions, messenger);
    }


    void initializeGenerationFromCatalog(
            CatalogContext catalogContext,
            final Connector conn,
            int hostId,
            HostMessenger messenger)
    {
        Set<Integer> partitions = new HashSet<Integer>();

        /*
         * Now create datasources based on the catalog
         */
        Iterator<ConnectorTableInfo> tableInfoIt = conn.getTableinfo().iterator();
        while (tableInfoIt.hasNext()) {
            ConnectorTableInfo next = tableInfoIt.next();
            Table table = next.getTable();
            partitions.addAll(addDataSources(table, hostId, catalogContext));
        }

        createAndRegisterAckMailboxes(partitions, messenger);
    }

    private void createAndRegisterAckMailboxes(final Set<Integer> localPartitions, HostMessenger messenger) {
        //Intentionally ignoring return values of all but the last operation
        m_zk.create(
                VoltZK.exportGenerations,
                null,
                Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT,
                new ZKUtil.StringCallback(),
                null);
        m_zkPath = VoltZK.exportGenerations + "/" + m_timestamp;
        m_zk.create(
                m_zkPath,
                null,
                Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT,
                new ZKUtil.StringCallback(),
                null);
        for (Integer partition : localPartitions) {
            m_zk.create(
                    m_zkPath + "/" + partition,
                    null,
                    Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT,
                    new ZKUtil.StringCallback(),
                    null);
        }

        m_mbox = new LocalMailbox(messenger) {
            @Override
            public void deliver(VoltMessage message) {
                if (message instanceof BinaryPayloadMessage) {
                    BinaryPayloadMessage bpm = (BinaryPayloadMessage)message;
                    ByteBuffer buf = ByteBuffer.wrap(bpm.m_payload);
                    final int partition = buf.getInt();
                    final int length = buf.getInt();
                    byte stringBytes[] = new byte[length];
                    buf.get(stringBytes);
                    String signature = new String(stringBytes, VoltDB.UTF8ENCODING);
                    final long ackUSO = buf.getLong();

                    final HashMap<String, ExportDataSource> partitionSources = m_dataSourcesByPartition.get(partition);
                    if (partitionSources == null) {
                        exportLog.error("Received an export ack for partition " + partition +
                                " which does not exist on this node");
                        return;
                    }

                    final ExportDataSource eds = partitionSources.get(signature);
                    if (eds == null) {
                        exportLog.error("Received an export ack for partition " + partition +
                                " source signature " + signature + " which does not exist on this node");
                        return;
                    }

                    eds.ack(ackUSO);
                } else {
                    exportLog.error("Receive unexpected message " + message + " in export subsystem");
                }
            }
        };
        messenger.createMailbox(null, m_mbox);

        List<ZKUtil.StringCallback> callbacks = new ArrayList<ZKUtil.StringCallback>();
        for (Integer partition : localPartitions) {
            ZKUtil.StringCallback callback = new ZKUtil.StringCallback();
            m_zk.create(
                    VoltZK.exportGenerations + "/" + partition + "/" + m_mbox.getHSId(),
                    null,
                    Ids.OPEN_ACL_UNSAFE,
                    CreateMode.EPHEMERAL,
                    callback,
                    null);
            callbacks.add(callback);
        }

        for (ZKUtil.StringCallback cb : callbacks) {
            try {
                cb.get();
            } catch (Throwable t) {
                Throwables.propagate(t);
            }
        }

        ListenableFuture<?> fut = m_childUpdatingThread.submit(new Runnable() {
            @Override
            public void run() {
                List<Pair<Integer,ZKUtil.ChildrenCallback>> callbacks =
                        new ArrayList<Pair<Integer, ZKUtil.ChildrenCallback>>();
                for (Integer partition : localPartitions) {
                    ZKUtil.ChildrenCallback callback = new ZKUtil.ChildrenCallback();
                    m_zk.getChildren(
                            m_zkPath + "/" + partition,
                            constructChildWatcher(),
                            callback,
                            null);
                    callbacks.add(Pair.of(partition, callback));
                }
                ImmutableMap.Builder<Integer, ImmutableList<Long>> mapBuilder =
                        ImmutableMap.builder();
                for (Pair<Integer, ZKUtil.ChildrenCallback> p : callbacks) {
                    final Integer partition = p.getFirst();
                    List<String> children = null;
                    try {
                        children = p.getSecond().getChildren();
                    } catch (InterruptedException e) {
                        Throwables.propagate(e);
                    } catch (KeeperException e) {
                        Throwables.propagate(e);
                    }
                    ImmutableList.Builder<Long> mailboxes = ImmutableList.builder();

                    for (String child : children) {
                        if (child.equals(Long.toString(m_mbox.getHSId()))) continue;
                        mailboxes.add(Long.valueOf(child));
                    }
                    mapBuilder.put(partition, mailboxes.build());
                }
                m_ackableMailboxes = mapBuilder.build();
            }
        });
        try {
            fut.get();
        } catch (Throwable t) {
            Throwables.propagate(t);
        }

    }

    private Watcher constructChildWatcher() {
        return new Watcher() {

            @Override
            public void process(final WatchedEvent event) {
                m_childUpdatingThread.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            handleChildUpdate(event);
                        } catch (Throwable t) {
                            VoltDB.crashLocalVoltDB("Error in export ack handling", false, t);
                        }
                    }
                });
            }

        };
    }

    private void handleChildUpdate(final WatchedEvent event) {
        m_zk.getChildren(event.getPath(), constructChildWatcher(), constructChildRetrievalCallback(), null);
    }

    private AsyncCallback.ChildrenCallback constructChildRetrievalCallback() {
        return new AsyncCallback.ChildrenCallback() {
            @Override
            public void processResult(final int rc, final String path, Object ctx,
                    final List<String> children) {
                m_childUpdatingThread.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            KeeperException.Code code = KeeperException.Code.get(rc);
                            if (code != KeeperException.Code.OK) {
                                throw KeeperException.create(code);
                            }

                            final String split[] = path.split("/");
                            final int partition = Integer.valueOf(split[split.length - 1]);
                            ImmutableMap.Builder<Integer, ImmutableList<Long>> mapBuilder =
                                    ImmutableMap.builder();
                            ImmutableList.Builder<Long> mailboxes = ImmutableList.builder();
                            for (String child : children) {
                                if (child.equals(Long.toString(m_mbox.getHSId()))) continue;
                                mailboxes.add(Long.valueOf(child));
                            }
                            mapBuilder.put(partition, mailboxes.build());
                            for (Map.Entry<Integer, ImmutableList<Long>> entry : m_ackableMailboxes.entrySet()) {
                                if (entry.getKey() == partition) continue;
                                mapBuilder.put(entry.getKey(), entry.getValue());
                            }
                            m_ackableMailboxes = mapBuilder.build();
                        } catch (Throwable t) {
                            VoltDB.crashLocalVoltDB("Error in export ack handling", false, t);
                        }
                    }
                });
            }

        };
    }

    public long getQueuedExportBytes(int partitionId, String signature) {
        //assert(m_dataSourcesByPartition.containsKey(partitionId));
        //assert(m_dataSourcesByPartition.get(partitionId).containsKey(delegateId));
        HashMap<String, ExportDataSource> sources = m_dataSourcesByPartition.get(partitionId);

        if (sources == null) {
            /*
             * This is fine. If the table is dropped it won't have an entry in the generation created
             * after the table was dropped.
             */
            //            exportLog.error("Could not find export data sources for generation " + m_timestamp + " partition "
            //                    + partitionId);
            return 0;
        }

        ExportDataSource source = sources.get(signature);
        if (source == null) {
            /*
             * This is fine. If the table is dropped it won't have an entry in the generation created
             * after the table was dropped.
             */
            //exportLog.error("Could not find export data source for generation " + m_timestamp + " partition " + partitionId +
            //        " signature " + signature);
            return 0;
        }
        return source.sizeInBytes();
    }

    /*
     * Create a datasource based on an ad file
     */
    private void addDataSource(
            File adFile,
            Set<Integer> partitions) throws IOException {
        m_numSources++;
        ExportDataSource source = new ExportDataSource( m_onSourceDrained, adFile);
        partitions.add(source.getPartitionId());
        exportLog.info("Creating ExportDataSource for " + adFile + " table " + source.getTableName() +
                " signature " + source.getSignature() + " partition id " + source.getPartitionId() +
                " bytes " + source.sizeInBytes());
        HashMap<String, ExportDataSource> dataSourcesForPartition =
            m_dataSourcesByPartition.get(source.getPartitionId());
        if (dataSourcesForPartition == null) {
            dataSourcesForPartition = new HashMap<String, ExportDataSource>();
            m_dataSourcesByPartition.put(source.getPartitionId(), dataSourcesForPartition);
        }
        dataSourcesForPartition.put( source.getSignature(), source);
    }

    /*
     * An unfortunate test only method for supplying a mock source
     */
    public void addDataSource(ExportDataSource source) {
        HashMap<String, ExportDataSource> dataSourcesForPartition =
            m_dataSourcesByPartition.get(source.getPartitionId());
        if (dataSourcesForPartition == null) {
            dataSourcesForPartition = new HashMap<String, ExportDataSource>();
            m_dataSourcesByPartition.put(source.getPartitionId(), dataSourcesForPartition);
        }
        dataSourcesForPartition.put(source.getSignature(), source);
    }

    // silly helper to add datasources for a table catalog object
    private Set<Integer> addDataSources(
            Table table, int hostId, CatalogContext catalogContext)
    {
        SiteTracker siteTracker = VoltDB.instance().getSiteTracker();
        List<Long> sites = siteTracker.getSitesForHost(hostId);

        Set<Integer> partitions = new HashSet<Integer>();
        for (Long site : sites) {
            Integer partition = siteTracker.getPartitionForSite(site);
            partitions.add(partition);

            /*
             * IOException can occur if there is a problem
             * with the persistent aspects of the datasource storage
             */
            try {
                HashMap<String, ExportDataSource> dataSourcesForPartition = m_dataSourcesByPartition.get(partition);
                if (dataSourcesForPartition == null) {
                    dataSourcesForPartition = new HashMap<String, ExportDataSource>();
                    m_dataSourcesByPartition.put(partition, dataSourcesForPartition);
                }
                ExportDataSource exportDataSource = new ExportDataSource(
                        m_onSourceDrained,
                        "database",
                        table.getTypeName(),
                        partition,
                        site,
                        table.getSignature(),
                        m_timestamp,
                        table.getColumns(),
                        m_directory.getPath());
                m_numSources++;
                exportLog.info("Creating ExportDataSource for table " + table.getTypeName() +
                        " signature " + table.getSignature() + " partition id " + partition);
                dataSourcesForPartition.put(table.getSignature(), exportDataSource);
            } catch (IOException e) {
                VoltDB.crashLocalVoltDB(e.getMessage(), true, e);
            }
        }
        return partitions;
    }

    public void pushExportBuffer(int partitionId, String signature, long uso,
            long bufferPtr, ByteBuffer buffer, boolean sync, boolean endOfStream) {
        //        System.out.println("In generation " + m_timestamp + " partition " + partitionId + " signature " + signature + (buffer == null ? " null buffer " : (" buffer length " + buffer.remaining())));
        //        for (Integer i : m_dataSourcesByPartition.keySet()) {
        //            System.out.println("Have partition " + i);
        //        }
        assert(m_dataSourcesByPartition.containsKey(partitionId));
        assert(m_dataSourcesByPartition.get(partitionId).containsKey(signature));
        HashMap<String, ExportDataSource> sources = m_dataSourcesByPartition.get(partitionId);

        if (sources == null) {
            exportLog.error("Could not find export data sources for partition "
                    + partitionId + " generation " + m_timestamp + " the export data is being discarded");
            DBBPool.deleteCharArrayMemory(bufferPtr);
            return;
        }

        ExportDataSource source = sources.get(signature);
        if (source == null) {
            exportLog.error("Could not find export data source for partition " + partitionId +
                    " signature " + signature + " generation " +
                    m_timestamp + " the export data is being discarded");
            DBBPool.deleteCharArrayMemory(bufferPtr);
            return;
        }

        source.pushExportBuffer(uso, bufferPtr, buffer, sync, endOfStream);
    }

    public void closeAndDelete() throws IOException {
        List<ListenableFuture<?>> tasks = new ArrayList<ListenableFuture<?>>();
        for (HashMap<String, ExportDataSource> map : m_dataSourcesByPartition.values()) {
            for (ExportDataSource source : map.values()) {
                tasks.add(source.closeAndDelete());
            }
        }
        try {
            Futures.allAsList(tasks).get();
        } catch (Exception e) {
            Throwables.propagateIfPossible(e, IOException.class);
        }
        VoltFile.recursivelyDelete(m_directory);

    }

    public void truncateExportToTxnId(long txnId, long[] perPartitionTxnIds) {
        // create an easy partitionId:txnId lookup.
        HashMap<Integer, Long> partitionToTxnId = new HashMap<Integer, Long>();
        for (long tid : perPartitionTxnIds) {
            partitionToTxnId.put(TxnEgo.getPartitionId(tid), tid);
        }

        List<ListenableFuture<?>> tasks = new ArrayList<ListenableFuture<?>>();

        // pre-iv2, the truncation point is the snapshot transaction id.
        // In iv2, truncation at the per-partition txn id recorded in the snapshot.
        for (HashMap<String, ExportDataSource> dataSources : m_dataSourcesByPartition.values()) {
            for (ExportDataSource source : dataSources.values()) {
                if (VoltDB.instance().isIV2Enabled()) {
                    Long truncationPoint = partitionToTxnId.get(source.getPartitionId());
                    if (truncationPoint == null) {
                        exportLog.error("Snapshot " + txnId +
                                " does not include truncation point for partition " +
                                source.getPartitionId());
                    }
                    else {
                        tasks.add(source.truncateExportToTxnId(truncationPoint));
                    }
                }
                else {
                    tasks.add(source.truncateExportToTxnId(txnId));
                }
            }
        }
        try {
            Futures.allAsList(tasks).get();
        } catch (Exception e) {
            VoltDB.crashLocalVoltDB("Unexpected exception truncating export data during snapshot restore. " +
                                    "You can back up export overflow data and start the " +
                                    "DB without it to get past this error", false, e);
        }
    }

    public void close() {
        List<ListenableFuture<?>> tasks = new ArrayList<ListenableFuture<?>>();
        for (HashMap<String, ExportDataSource> sources : m_dataSourcesByPartition.values()) {
            for (ExportDataSource source : sources.values()) {
                tasks.add(source.close());
            }
        }
        try {
            Futures.allAsList(tasks).get();
        } catch (Exception e) {
            //Logging of errors  is done inside the tasks so nothing to do here
            //intentionally not failing if there is an issue with close
            exportLog.error("Error closing export data sources", e);
        }
    }
}
