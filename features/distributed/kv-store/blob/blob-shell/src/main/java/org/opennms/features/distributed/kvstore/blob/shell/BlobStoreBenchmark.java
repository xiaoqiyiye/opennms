/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2019 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2019 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.features.distributed.kvstore.blob.shell;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.opennms.features.distributed.kvstore.api.BlobStore;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;

@Command(scope = "opennms-kv-blob", name = "benchmark", description = "Benchmark the blob store's throughput")
@Service
public class BlobStoreBenchmark implements Action {
    @Reference
    private BlobStore blobStore;

    @Argument(index = 0, description = "The payload size in bytes", required = true)
    private int payloadSize;

    @Argument(index = 1, description = "The number of records", required = true)
    private int numberOfRecords;

    @Option(name = "-t", aliases = "--just-timestamp", description = "Whether or not to read just the timestamp")
    private boolean readJustTimestamp = false;

    @Option(name = "-a", aliases = "--async", description = "Whether or not to use async")
    private boolean async = false;

    private final String CONTEXT = "benchmark";

    private final String KEY = "test";

    private final MetricRegistry metrics = new MetricRegistry();

    private final StringBuilder resultsBuilder = new StringBuilder();

    private byte[] writePayload;
    
    private final Executor resultsExecutor = Executors.newFixedThreadPool(100);

    @Override
    public Object execute() throws InterruptedException {
        System.out.println(String.format("BlobStore implementation in use: %s", blobStore.getBackingImplName()));
        resultsBuilder.append('\n');
        writePayload = new byte[payloadSize];
        benchmarkMethod("write", this::performAsyncWrite, this::performSyncWrite, this::generateWriteResults);
        benchmarkMethod("read", this::performAsyncRead, this::performSyncRead, this::generateReadResults);
        System.out.println(resultsBuilder.toString());
        return null;
    }

    private CompletableFuture<?> performAsyncWrite(String key, AtomicLong totalTime, Histogram results) {
        long start = System.currentTimeMillis();

        CompletableFuture<?> future = blobStore.putAsync(key, writePayload, CONTEXT, (int) TimeUnit.SECONDS.convert(1,
                TimeUnit.HOURS));

        future.thenAccept(v -> {
            long elapsed = System.currentTimeMillis() - start;
            results.update(elapsed);
            totalTime.addAndGet(elapsed);
        });

        return future;
    }

    private void performSyncWrite(String key, AtomicLong totalTime, Histogram results) {
        long start = System.currentTimeMillis();

        blobStore.put(key, writePayload, CONTEXT, (int) TimeUnit.SECONDS.convert(1, TimeUnit.HOURS));

        long elapsed = System.currentTimeMillis() - start;
        results.update(elapsed);
        totalTime.addAndGet(elapsed);
    }

    private void generateWriteResults(AtomicLong totalTime, Histogram results) {
        resultsBuilder.append(String.format("Wrote %d records in %d milliseconds\n", numberOfRecords, totalTime.get()));
        resultsBuilder.append(String.format("Average write latency: %.2fms\n", results.getSnapshot().getMean()));
        double throughPut = ((payloadSize * numberOfRecords) / 1024.0) / (totalTime.get() / 1000.0);
        resultsBuilder.append(String.format("Average write throughput: %.2f KB/s\n\n", throughPut));
    }

    private CompletableFuture<?> performAsyncRead(String key, AtomicLong totalTime,
                                                  Histogram results) {
        CompletableFuture<?> future;
        long start = System.currentTimeMillis();

        if (readJustTimestamp) {
            future = blobStore.getLastUpdatedAsync(key, CONTEXT);
        } else {
            future = blobStore.getAsync(key, CONTEXT);
        }

        future.whenCompleteAsync((v,t) -> {
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("adding "+elapsed+" ms");
            results.update(elapsed);
            totalTime.addAndGet(elapsed);
        }, resultsExecutor);

        return future;
    }

    private void performSyncRead(String key, AtomicLong totalTime, Histogram results) {
        long start = System.currentTimeMillis();

        if (readJustTimestamp) {
            blobStore.getLastUpdated(key, CONTEXT);
        } else {
            blobStore.get(key, CONTEXT);
        }

        long elapsed = System.currentTimeMillis() - start;
        results.update(elapsed);
        totalTime.addAndGet(elapsed);
    }

    private void generateReadResults(AtomicLong totalTime, Histogram results) {
        resultsBuilder.append(String.format("Read %d records in %d milliseconds\n", numberOfRecords, totalTime.get()));
        resultsBuilder.append(String.format("Average read latency: %.2fms\n", results.getSnapshot().getMean()));

        if (!readJustTimestamp) {
            double throughPut = ((payloadSize * numberOfRecords) / 1024.0) / (totalTime.get() / 1000.0);
            resultsBuilder.append(String.format("Average read throughput: %.2f KB/s\n", throughPut));
        }
    }

    private void benchmarkMethod(String methodType,
                                 BenchmarkMethodAsync performAsyncFunction,
                                 BenchmarkMethod performSyncFunction,
                                 BiConsumer<AtomicLong, Histogram> generateResults) throws InterruptedException {
        System.out.print(String.format("Benchmarking %s performance...", methodType));

        List<CompletableFuture<?>> futures = new ArrayList<>();
        Histogram results = metrics.histogram(String.format("%s times", methodType));
        AtomicLong totalTime = new AtomicLong(0);

        CompletableFuture<Boolean> benchmarkFuture = CompletableFuture.supplyAsync(() -> {
            for (int i = 0; i < numberOfRecords; i++) {
                String key = String.format("%s-%d", KEY, i);

                if (this.async) {
                    futures.add(performAsyncFunction.performBenchmark(key, totalTime, results));
                } else {
                    performSyncFunction.performBenchmark(key, totalTime, results);
                }
            }

            if (this.async) {
                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            return true;
        });

        // Give user feedback while the benchmark is running
        while (!benchmarkFuture.getNow(false)) {
            System.out.print('.');
            Thread.sleep(1000);
        }

        System.out.println("done");
        generateResults.accept(totalTime, results);
    }

    @FunctionalInterface
    private interface BenchmarkMethod {
        void performBenchmark(String method, AtomicLong totalTime, Histogram results);
    }

    @FunctionalInterface
    private interface BenchmarkMethodAsync {
        CompletableFuture<?> performBenchmark(String method, AtomicLong totalTime, Histogram results);
    }
}
