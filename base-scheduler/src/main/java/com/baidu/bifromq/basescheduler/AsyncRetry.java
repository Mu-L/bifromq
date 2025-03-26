/*
 * Copyright (c) 2025. The BifroMQ Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package com.baidu.bifromq.basescheduler;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/**
 * An asynchronous retry utility with exponential backoff.
 */
public class AsyncRetry {

    /**
     * Executes an asynchronous task with exponential backoff retry.
     *
     * <p>The method repeatedly invokes the given {@code taskSupplier} until the result satisfies
     * the {@code successPredicate}. The delay between retries increases exponentially (starting from
     * {@code initialBackoffMillis}). If the total accumulated delay exceeds {@code maxDelayMillis}, the returned
     * CompletableFuture is completed exceptionally with a TimeoutException.
     *
     * @param taskSupplier        A supplier that returns a CompletableFuture representing the asynchronous task.
     * @param successPredicate    A predicate to test whether the task's result is successful.
     * @param initialBackoffNanos The initial delay (in nanoseconds) for retrying. 0 means no retry.
     * @param maxDelayNanos       The maximum allowed accumulated delay (in nanoseconds) before timing out.
     * @param <T>                 The type of the task result.
     * @return A CompletableFuture that completes with the task result if successful, or exceptionally with a
     * TimeoutException if max delay is exceeded.
     */
    public static <T> CompletableFuture<T> retryAsync(Supplier<CompletableFuture<T>> taskSupplier,
                                                      BiPredicate<T, Throwable> successPredicate,
                                                      long initialBackoffNanos,
                                                      long maxDelayNanos) {
        assert initialBackoffNanos <= maxDelayNanos;
        CompletableFuture<T> onDone = new CompletableFuture<>();
        retryAsync(taskSupplier, successPredicate, initialBackoffNanos, maxDelayNanos, 0, 0, onDone);
        return onDone;
    }

    private static <T> void retryAsync(Supplier<CompletableFuture<T>> taskSupplier,
                                       BiPredicate<T, Throwable> successPredicate,
                                       long initialBackoffNanos,
                                       long maxDelayNanos,
                                       int retryCount,
                                       long delayNanosSoFar,
                                       CompletableFuture<T> onDone) {

        if (initialBackoffNanos > 0 && delayNanosSoFar >= maxDelayNanos) {
            onDone.completeExceptionally(new TimeoutException("Max retry delay exceeded"));
            return;
        }

        // Execute the asynchronous task.
        executeTask(taskSupplier).whenComplete((result, t) -> {
            // If the result satisfies the success predicate, return it.
            if (initialBackoffNanos == 0 || successPredicate.test(result, t)) {
                if (t != null) {
                    onDone.completeExceptionally(t);
                } else {
                    onDone.complete(result);
                }
            } else {
                long delay = initialBackoffNanos * (1L << retryCount);
                if (delayNanosSoFar + delay > maxDelayNanos) {
                    delay = maxDelayNanos - delayNanosSoFar;
                }
                long delayMillisSoFarNew = delayNanosSoFar + delay;
                // Otherwise, schedule a retry after the calculated delay.
                Executor delayExecutor = CompletableFuture.delayedExecutor(delay, TimeUnit.NANOSECONDS);
                CompletableFuture.runAsync(() -> retryAsync(
                    taskSupplier,
                    successPredicate,
                    initialBackoffNanos,
                    maxDelayNanos,
                    retryCount + 1,
                    delayMillisSoFarNew, onDone), delayExecutor);
            }
        });
    }

    private static <T> CompletableFuture<T> executeTask(Supplier<CompletableFuture<T>> taskSupplier) {
        try {
            return taskSupplier.get();
        } catch (Throwable e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}