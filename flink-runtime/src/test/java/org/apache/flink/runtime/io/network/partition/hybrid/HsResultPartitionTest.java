/*
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

package org.apache.flink.runtime.io.network.partition.hybrid;

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.core.memory.MemorySegment;
import org.apache.flink.core.memory.MemorySegmentFactory;
import org.apache.flink.core.testutils.CheckedThread;
import org.apache.flink.runtime.event.AbstractEvent;
import org.apache.flink.runtime.executiongraph.IOMetrics;
import org.apache.flink.runtime.io.disk.BatchShuffleReadBufferPool;
import org.apache.flink.runtime.io.disk.FileChannelManager;
import org.apache.flink.runtime.io.disk.FileChannelManagerImpl;
import org.apache.flink.runtime.io.network.api.EndOfPartitionEvent;
import org.apache.flink.runtime.io.network.api.serialization.EventSerializer;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.buffer.BufferPool;
import org.apache.flink.runtime.io.network.buffer.NetworkBuffer;
import org.apache.flink.runtime.io.network.buffer.NetworkBufferPool;
import org.apache.flink.runtime.io.network.partition.BufferAvailabilityListener;
import org.apache.flink.runtime.io.network.partition.NoOpBufferAvailablityListener;
import org.apache.flink.runtime.io.network.partition.ResultPartitionID;
import org.apache.flink.runtime.io.network.partition.ResultPartitionManager;
import org.apache.flink.runtime.io.network.partition.ResultPartitionType;
import org.apache.flink.runtime.io.network.partition.ResultSubpartition;
import org.apache.flink.runtime.io.network.partition.ResultSubpartitionView;
import org.apache.flink.runtime.io.network.partition.hybrid.HybridShuffleConfiguration.SpillingStrategyType;
import org.apache.flink.runtime.metrics.groups.TaskIOMetricGroup;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link HsResultPartition}. */
class HsResultPartitionTest {

    private static final int bufferSize = 1024;

    private static final int totalBuffers = 1000;

    private static final int totalBytes = 32 * 1024 * 1024;

    private static final int numThreads = 4;

    private FileChannelManager fileChannelManager;

    private NetworkBufferPool globalPool;

    private BatchShuffleReadBufferPool readBufferPool;

    private ScheduledExecutorService readIOExecutor;

    private TaskIOMetricGroup taskIOMetricGroup;

    @TempDir public Path tempDataPath;

    @BeforeEach
    void before() {
        fileChannelManager =
                new FileChannelManagerImpl(new String[] {tempDataPath.toString()}, "testing");
        globalPool = new NetworkBufferPool(totalBuffers, bufferSize);
        readBufferPool = new BatchShuffleReadBufferPool(totalBytes, bufferSize);
        readIOExecutor = Executors.newScheduledThreadPool(numThreads);
    }

    @AfterEach
    void after() throws Exception {
        fileChannelManager.close();
        globalPool.destroy();
        readBufferPool.destroy();
        readIOExecutor.shutdown();
    }

    @Test
    void testEmit() throws Exception {
        int numBuffers = 100;
        int numSubpartitions = 10;
        int numRecords = 1000;
        Random random = new Random();

        BufferPool bufferPool = globalPool.createBufferPool(numBuffers, numBuffers);

        try (HsResultPartition partition = createHsResultPartition(numSubpartitions, bufferPool)) {
            Queue<Tuple2<ByteBuffer, Buffer.DataType>>[] dataWritten = new Queue[numSubpartitions];
            Queue<Buffer>[] buffersRead = new Queue[numSubpartitions];
            for (int i = 0; i < numSubpartitions; ++i) {
                dataWritten[i] = new ArrayDeque<>();
                buffersRead[i] = new ArrayDeque<>();
            }

            int[] numBytesWritten = new int[numSubpartitions];
            int[] numBytesRead = new int[numSubpartitions];
            Arrays.fill(numBytesWritten, 0);
            Arrays.fill(numBytesRead, 0);

            for (int i = 0; i < numRecords; ++i) {
                ByteBuffer record = generateRandomData(random.nextInt(2 * bufferSize) + 1, random);
                boolean isBroadCast = random.nextBoolean();

                if (isBroadCast) {
                    partition.broadcastRecord(record);
                    for (int subpartition = 0; subpartition < numSubpartitions; ++subpartition) {
                        recordDataWritten(
                                record,
                                dataWritten,
                                subpartition,
                                numBytesWritten,
                                Buffer.DataType.DATA_BUFFER);
                    }
                } else {
                    int subpartition = random.nextInt(numSubpartitions);
                    partition.emitRecord(record, subpartition);
                    recordDataWritten(
                            record,
                            dataWritten,
                            subpartition,
                            numBytesWritten,
                            Buffer.DataType.DATA_BUFFER);
                }
            }

            partition.finish();

            for (int subpartition = 0; subpartition < numSubpartitions; ++subpartition) {
                ByteBuffer record = EventSerializer.toSerializedEvent(EndOfPartitionEvent.INSTANCE);
                recordDataWritten(
                        record,
                        dataWritten,
                        subpartition,
                        numBytesWritten,
                        Buffer.DataType.EVENT_BUFFER);
            }

            Tuple2<ResultSubpartitionView, TestingBufferAvailabilityListener>[] viewAndListeners =
                    createSubpartitionViews(partition, numSubpartitions);
            readData(
                    viewAndListeners,
                    (buffer, subpartitionId) -> {
                        int numBytes = buffer.readableBytes();
                        numBytesRead[subpartitionId] += numBytes;

                        MemorySegment segment =
                                MemorySegmentFactory.allocateUnpooledSegment(numBytes);
                        segment.put(0, buffer.getNioBufferReadable(), numBytes);
                        buffersRead[subpartitionId].add(
                                new NetworkBuffer(
                                        segment, (buf) -> {}, buffer.getDataType(), numBytes));
                    });
            checkWriteReadResult(
                    numSubpartitions, numBytesWritten, numBytesRead, dataWritten, buffersRead);
        }
    }

    @Test
    void testBroadcastEvent() throws Exception {
        final int numBuffers = 1;
        BufferPool bufferPool = globalPool.createBufferPool(numBuffers, numBuffers);
        try (HsResultPartition resultPartition = createHsResultPartition(2, bufferPool)) {
            resultPartition.broadcastEvent(EndOfPartitionEvent.INSTANCE, false);
            // broadcast event does not request buffer
            assertThat(bufferPool.getNumberOfAvailableMemorySegments()).isEqualTo(1);

            Tuple2[] viewAndListeners = createSubpartitionViews(resultPartition, 2);

            boolean[] receivedEvent = new boolean[2];
            readData(
                    viewAndListeners,
                    (buffer, subpartition) -> {
                        assertThat(buffer.getDataType().isEvent()).isTrue();
                        try {
                            AbstractEvent event =
                                    EventSerializer.fromSerializedEvent(
                                            buffer.readOnlySlice().getNioBufferReadable(),
                                            HsResultPartitionTest.class.getClassLoader());
                            assertThat(event).isInstanceOf(EndOfPartitionEvent.class);
                            receivedEvent[subpartition] = true;
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });

            assertThat(receivedEvent).containsExactly(true, true);
        }
    }

    @Test
    void testClose() throws Exception {
        final int numBuffers = 1;

        BufferPool bufferPool = globalPool.createBufferPool(numBuffers, numBuffers);
        HsResultPartition partition = createHsResultPartition(1, bufferPool);

        partition.close();
        // emit data to closed partition will throw exception.
        assertThatThrownBy(() -> partition.emitRecord(ByteBuffer.allocate(bufferSize), 0));
    }

    @Test
    @Timeout(30)
    void testRelease() throws Exception {
        final int numBuffers = 10;

        BufferPool bufferPool = globalPool.createBufferPool(numBuffers, numBuffers);
        HsResultPartition partition = createHsResultPartition(2, bufferPool);

        partition.emitRecord(ByteBuffer.allocate(bufferSize * numBuffers), 1);
        assertThat(bufferPool.bestEffortGetNumOfUsedBuffers()).isEqualTo(numBuffers);

        partition.close();
        assertThat(bufferPool.isDestroyed()).isTrue();

        partition.release();

        while (checkNotNull(fileChannelManager.getPaths()[0].listFiles()).length != 0) {
            Thread.sleep(10);
        }

        assertThat(totalBuffers).isEqualTo(globalPool.getNumberOfAvailableMemorySegments());
    }

    @Test
    void testCreateSubpartitionViewAfterRelease() throws Exception {
        final int numBuffers = 10;
        BufferPool bufferPool = globalPool.createBufferPool(numBuffers, numBuffers);
        HsResultPartition resultPartition = createHsResultPartition(2, bufferPool);
        resultPartition.release();
        assertThatThrownBy(
                        () ->
                                resultPartition.createSubpartitionView(
                                        0, new NoOpBufferAvailablityListener()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testAvailability() throws Exception {
        final int numBuffers = 2;

        BufferPool bufferPool = globalPool.createBufferPool(numBuffers, numBuffers);
        HsResultPartition partition = createHsResultPartition(1, bufferPool);

        partition.emitRecord(ByteBuffer.allocate(bufferSize * numBuffers), 0);
        assertThat(partition.isAvailable()).isFalse();

        // release partition to recycle buffer.
        partition.close();
        partition.release();

        assertThat(partition.isAvailable()).isTrue();
    }

    @Test
    void testMetricsUpdate() throws Exception {
        BufferPool bufferPool = globalPool.createBufferPool(3, 3);
        try (HsResultPartition partition = createHsResultPartition(2, bufferPool)) {
            partition.emitRecord(ByteBuffer.allocate(bufferSize), 0);
            partition.broadcastRecord(ByteBuffer.allocate(bufferSize));
            assertThat(taskIOMetricGroup.getNumBuffersOutCounter().getCount()).isEqualTo(3);
            assertThat(taskIOMetricGroup.getNumBytesOutCounter().getCount())
                    .isEqualTo(3 * bufferSize);
            IOMetrics ioMetrics = taskIOMetricGroup.createSnapshot();
            assertThat(ioMetrics.getNumBytesProducedOfPartitions())
                    .hasSize(1)
                    .containsValue((long) 2 * bufferSize);
        }
    }

    private static void recordDataWritten(
            ByteBuffer record,
            Queue<Tuple2<ByteBuffer, Buffer.DataType>>[] dataWritten,
            int subpartition,
            int[] numBytesWritten,
            Buffer.DataType dataType) {
        record.rewind();
        dataWritten[subpartition].add(Tuple2.of(record, dataType));
        numBytesWritten[subpartition] += record.remaining();
    }

    private long readData(
            Tuple2<ResultSubpartitionView, TestingBufferAvailabilityListener>[] viewAndListeners,
            BiConsumer<Buffer, Integer> bufferProcessor)
            throws Exception {
        AtomicInteger dataSize = new AtomicInteger(0);
        AtomicInteger numEndOfPartitionEvents = new AtomicInteger(0);
        CheckedThread[] subpartitionViewThreads = new CheckedThread[viewAndListeners.length];
        for (int i = 0; i < viewAndListeners.length; i++) {
            // start thread for each view.
            final int subpartition = i;
            CheckedThread subpartitionViewThread =
                    new CheckedThread() {
                        @Override
                        public void go() throws Exception {
                            ResultSubpartitionView view = viewAndListeners[subpartition].f0;
                            while (true) {
                                ResultSubpartition.BufferAndBacklog bufferAndBacklog =
                                        view.getNextBuffer();
                                if (bufferAndBacklog == null) {
                                    viewAndListeners[subpartition].f1.waitForData();
                                    continue;
                                }
                                Buffer buffer = bufferAndBacklog.buffer();
                                bufferProcessor.accept(buffer, subpartition);
                                dataSize.addAndGet(buffer.readableBytes());
                                buffer.recycleBuffer();

                                if (!buffer.isBuffer()) {
                                    numEndOfPartitionEvents.incrementAndGet();
                                    view.releaseAllResources();
                                    break;
                                }
                                if (bufferAndBacklog.getNextDataType() == Buffer.DataType.NONE) {
                                    viewAndListeners[subpartition].f1.waitForData();
                                }
                            }
                        }
                    };
            subpartitionViewThreads[subpartition] = subpartitionViewThread;
            subpartitionViewThread.start();
        }
        for (CheckedThread thread : subpartitionViewThreads) {
            thread.sync();
        }
        return dataSize.get();
    }

    private static ByteBuffer generateRandomData(int dataSize, Random random) {
        byte[] dataWritten = new byte[dataSize];
        random.nextBytes(dataWritten);
        return ByteBuffer.wrap(dataWritten);
    }

    private HsResultPartition createHsResultPartition(
            int numSubpartitions, BufferPool bufferPool, int numBuffersTriggerSpilling)
            throws IOException {
        HsResultPartition hsResultPartition =
                new HsResultPartition(
                        "HsResultPartitionTest",
                        0,
                        new ResultPartitionID(),
                        ResultPartitionType.HYBRID_FULL,
                        numSubpartitions,
                        numSubpartitions,
                        readBufferPool,
                        readIOExecutor,
                        new ResultPartitionManager(),
                        fileChannelManager.createChannel().getPath(),
                        bufferSize,
                        HybridShuffleConfiguration.builder(
                                        numSubpartitions, readBufferPool.getNumBuffersPerRequest())
                                .setSpillingStrategyType(SpillingStrategyType.FULL)
                                .setFullStrategyNumBuffersTriggerSpilling(numBuffersTriggerSpilling)
                                .build(),
                        null,
                        () -> bufferPool);
        hsResultPartition.setup();
        return hsResultPartition;
    }

    private HsResultPartition createHsResultPartition(int numSubpartitions, BufferPool bufferPool)
            throws IOException {
        HsResultPartition hsResultPartition =
                new HsResultPartition(
                        "HsResultPartitionTest",
                        0,
                        new ResultPartitionID(),
                        ResultPartitionType.HYBRID_FULL,
                        numSubpartitions,
                        numSubpartitions,
                        readBufferPool,
                        readIOExecutor,
                        new ResultPartitionManager(),
                        fileChannelManager.createChannel().getPath(),
                        bufferSize,
                        HybridShuffleConfiguration.builder(
                                        numSubpartitions, readBufferPool.getNumBuffersPerRequest())
                                .build(),
                        null,
                        () -> bufferPool);
        taskIOMetricGroup =
                UnregisteredMetricGroups.createUnregisteredTaskMetricGroup().getIOMetricGroup();
        hsResultPartition.setup();
        hsResultPartition.setMetricGroup(taskIOMetricGroup);
        return hsResultPartition;
    }

    private static void checkWriteReadResult(
            int numSubpartitions,
            int[] numBytesWritten,
            int[] numBytesRead,
            Queue<Tuple2<ByteBuffer, Buffer.DataType>>[] dataWritten,
            Queue<Buffer>[] buffersRead) {
        for (int subpartitionIndex = 0; subpartitionIndex < numSubpartitions; ++subpartitionIndex) {
            assertThat(numBytesWritten[subpartitionIndex])
                    .isEqualTo(numBytesRead[subpartitionIndex]);

            List<Tuple2<ByteBuffer, Buffer.DataType>> eventsWritten = new ArrayList<>();
            List<Buffer> eventsRead = new ArrayList<>();

            ByteBuffer subpartitionDataWritten =
                    ByteBuffer.allocate(numBytesWritten[subpartitionIndex]);
            for (Tuple2<ByteBuffer, Buffer.DataType> bufferDataTypeTuple :
                    dataWritten[subpartitionIndex]) {
                subpartitionDataWritten.put(bufferDataTypeTuple.f0);
                bufferDataTypeTuple.f0.rewind();
                if (bufferDataTypeTuple.f1.isEvent()) {
                    eventsWritten.add(bufferDataTypeTuple);
                }
            }

            ByteBuffer subpartitionDataRead = ByteBuffer.allocate(numBytesRead[subpartitionIndex]);
            for (Buffer buffer : buffersRead[subpartitionIndex]) {
                subpartitionDataRead.put(buffer.getNioBufferReadable());
                if (!buffer.isBuffer()) {
                    eventsRead.add(buffer);
                }
            }

            subpartitionDataWritten.flip();
            subpartitionDataRead.flip();
            assertThat(subpartitionDataWritten).isEqualTo(subpartitionDataRead);

            assertThat(eventsWritten.size()).isEqualTo(eventsRead.size());
            for (int i = 0; i < eventsWritten.size(); i++) {
                assertThat(eventsWritten.get(i).f1).isEqualTo(eventsRead.get(i).getDataType());
                assertThat(eventsWritten.get(i).f0)
                        .isEqualTo(eventsRead.get(i).getNioBufferReadable());
            }
        }
    }

    private Tuple2<ResultSubpartitionView, TestingBufferAvailabilityListener>[]
            createSubpartitionViews(HsResultPartition partition, int numSubpartitions)
                    throws Exception {
        Tuple2<ResultSubpartitionView, TestingBufferAvailabilityListener>[] viewAndListeners =
                new Tuple2[numSubpartitions];
        for (int subpartition = 0; subpartition < numSubpartitions; ++subpartition) {
            TestingBufferAvailabilityListener listener = new TestingBufferAvailabilityListener();
            viewAndListeners[subpartition] =
                    Tuple2.of(partition.createSubpartitionView(subpartition, listener), listener);
        }
        return viewAndListeners;
    }

    private static final class TestingBufferAvailabilityListener
            implements BufferAvailabilityListener {

        private int numNotifications;

        @Override
        public synchronized void notifyDataAvailable() {
            if (numNotifications == 0) {
                notifyAll();
            }
            ++numNotifications;
        }

        public synchronized void waitForData() throws InterruptedException {
            if (numNotifications == 0) {
                wait();
            }
            numNotifications = 0;
        }
    }
}
