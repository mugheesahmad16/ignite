/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.util.future;

import org.apache.ignite.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.util.tostring.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.lang.*;
import org.jetbrains.annotations.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/**
 * Future adapter.
 */
public class GridFutureAdapter<R> extends AbstractQueuedSynchronizer implements IgniteInternalFuture<R> {
    /** */
    private static final long serialVersionUID = 0L;

    /** Initial state. */
    private static final int INIT = 0;

    /** Cancelled state. */
    private static final int CANCELLED = 1;

    /** Done state. */
    private static final int DONE = 2;

    /** Result. */
    @GridToStringInclude
    private R res;

    /** Error. */
    private Throwable err;

    /** Future start time. */
    private final long startTime = U.currentTimeMillis();

    /** Future end time. */
    private volatile long endTime;

    /** Asynchronous listeners. */
    private Collection<IgniteInClosure<? super IgniteInternalFuture<R>>> lsnrs;

    /** */
    private final Object mux = new Object();

    /** {@inheritDoc} */
    @Override public long startTime() {
        return startTime;
    }

    /** {@inheritDoc} */
    @Override public long duration() {
        long endTime = this.endTime;

        return endTime == 0 ? U.currentTimeMillis() - startTime : endTime - startTime;
    }

    /**
     * @return Future end time.
     */
    public long endTime() {
        return endTime;
    }

    /**
     * @return Value of error.
     */
    protected Throwable error() {
        return err;
    }

    /**
     * @return Value of result.
     */
    protected R result() {
        return res;
    }

    /** {@inheritDoc} */
    @Override public R get() throws IgniteCheckedException {
        try {
            if (endTime == 0)
                acquireSharedInterruptibly(0);

            if (getState() == CANCELLED)
                throw new IgniteFutureCancelledCheckedException("Future was cancelled: " + this);

            if (err != null)
                throw U.cast(err);

            return res;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IgniteInterruptedCheckedException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public R get(long timeout) throws IgniteCheckedException {
        // Do not replace with static import, as it may not compile.
        return get(timeout, TimeUnit.MILLISECONDS);
    }

    /** {@inheritDoc} */
    @Override public R get(long timeout, TimeUnit unit) throws IgniteCheckedException {
        A.ensure(timeout >= 0, "timeout cannot be negative: " + timeout);
        A.notNull(unit, "unit");

        try {
            return get0(unit.toNanos(timeout));
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IgniteInterruptedCheckedException("Got interrupted while waiting for future to complete.", e);
        }
    }

    /**
     * @param nanosTimeout Timeout (nanoseconds).
     * @return Result.
     * @throws InterruptedException If interrupted.
     * @throws IgniteFutureTimeoutCheckedException If timeout reached before computation completed.
     * @throws IgniteCheckedException If error occurred.
     */
    @Nullable protected R get0(long nanosTimeout) throws InterruptedException, IgniteCheckedException {
        if (endTime == 0 && !tryAcquireSharedNanos(0, nanosTimeout))
            throw new IgniteFutureTimeoutCheckedException("Timeout was reached before computation completed.");

        if (getState() == CANCELLED)
            throw new IgniteFutureCancelledCheckedException("Future was cancelled: " + this);

        if (err != null)
            throw U.cast(err);

        return res;
    }

    /** {@inheritDoc} */
    @Override public void listenAsync(@Nullable final IgniteInClosure<? super IgniteInternalFuture<R>> lsnr) {
        if (lsnr != null) {
            boolean done = isDone();

            if (!done) {
                synchronized (mux) {
                    done = isDone(); // Double check.

                    if (!done) {
                        if (lsnrs == null)
                            lsnrs = new ArrayList<>();

                        lsnrs.add(lsnr);
                    }
                }
            }

            if (done) {
                notifyListener(lsnr);
            }
        }
    }

    /** {@inheritDoc} */
    @Override public <T> IgniteInternalFuture<T> chain(final IgniteClosure<? super IgniteInternalFuture<R>, T> doneCb) {
        return new ChainFuture<>(this, doneCb);
    }

    /**
     * Notifies all registered listeners.
     */
    private void notifyListeners() {
        final Collection<IgniteInClosure<? super IgniteInternalFuture<R>>> lsnrs0;

        synchronized (mux) {
            lsnrs0 = lsnrs;

            if (lsnrs0 == null || lsnrs0.isEmpty())
                return;

            lsnrs = null;
        }

        assert !lsnrs0.isEmpty();

        for (IgniteInClosure<? super IgniteInternalFuture<R>> lsnr : lsnrs0)
            notifyListener(lsnr);
    }

    /**
     * Notifies single listener.
     *
     * @param lsnr Listener.
     */
    private void notifyListener(IgniteInClosure<? super IgniteInternalFuture<R>> lsnr) {
        assert lsnr != null;

        try {
            lsnr.apply(this);
        }
        catch (IllegalStateException e) {
            U.warn(null, "Failed to notify listener (is grid stopped?) [fut=" + this +
                ", lsnr=" + lsnr + ", err=" + e.getMessage() + ']');
        }
        catch (RuntimeException | Error e) {
            U.error(null, "Failed to notify listener: " + lsnr, e);

            throw e;
        }
    }

    /**
     * Default no-op implementation that always returns {@code false}.
     * Futures that do support cancellation should override this method
     * and call {@link #onCancelled()} callback explicitly if cancellation
     * indeed did happen.
     */
    @Override public boolean cancel() throws IgniteCheckedException {
        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean isDone() {
        // Don't check for "valid" here, as "done" flag can be read
        // even in invalid state.
        return endTime != 0;
    }

    /**
     * @return Checks is future is completed with exception.
     */
    public boolean isFailed() {
        // Must read endTime first.
        return endTime != 0 && err != null;
    }

    /** {@inheritDoc} */
    @Override public boolean isCancelled() {
        return getState() == CANCELLED;
    }

    /**
     * Callback to notify that future is finished with {@code null} result.
     * This method must delegate to {@link #onDone(Object, Throwable)} method.
     *
     * @return {@code True} if result was set by this call.
     */
    public final boolean onDone() {
        return onDone(null, null);
    }

    /**
     * Callback to notify that future is finished.
     * This method must delegate to {@link #onDone(Object, Throwable)} method.
     *
     * @param res Result.
     * @return {@code True} if result was set by this call.
     */
    public final boolean onDone(@Nullable R res) {
        return onDone(res, null);
    }

    /**
     * Callback to notify that future is finished.
     * This method must delegate to {@link #onDone(Object, Throwable)} method.
     *
     * @param err Error.
     * @return {@code True} if result was set by this call.
     */
    public final boolean onDone(@Nullable Throwable err) {
        return onDone(null, err);
    }

    /**
     * Callback to notify that future is finished. Note that if non-{@code null} exception is passed in
     * the result value will be ignored.
     *
     * @param res Optional result.
     * @param err Optional error.
     * @return {@code True} if result was set by this call.
     */
    public boolean onDone(@Nullable R res, @Nullable Throwable err) {
        return onDone(res, err, false);
    }

    /**
     * @param res Result.
     * @param err Error.
     * @param cancel {@code True} if future is being cancelled.
     * @return {@code True} if result was set by this call.
     */
    private boolean onDone(@Nullable R res, @Nullable Throwable err, boolean cancel) {
        boolean notify = false;

        try {
            if (compareAndSetState(INIT, cancel ? CANCELLED : DONE)) {
                this.res = res;
                this.err = err;

                notify = true;

                releaseShared(0);

                return true;
            }

            return false;
        }
        finally {
            if (notify)
                notifyListeners();
        }
    }

    /**
     * Callback to notify that future is cancelled.
     *
     * @return {@code True} if cancel flag was set by this call.
     */
    public boolean onCancelled() {
        return onDone(null, null, true);
    }

    /** {@inheritDoc} */
    @Override protected final int tryAcquireShared(int ignore) {
        return endTime != 0 ? 1 : -1;
    }

    /** {@inheritDoc} */
    @Override protected final boolean tryReleaseShared(int ignore) {
        endTime = U.currentTimeMillis();

        // Always signal after setting final done status.
        return true;
    }

    /**
     * @return String representation of state.
     */
    private String state() {
        int s = getState();

        return s == INIT ? "INIT" : s == CANCELLED ? "CANCELLED" : "DONE";
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridFutureAdapter.class, this, "state", state());
    }

    /**
     *
     */
    private static class ChainFuture<R, T> extends GridFutureAdapter<T> {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        private GridFutureAdapter<R> fut;

        /** */
        private IgniteClosure<? super IgniteInternalFuture<R>, T> doneCb;

        /**
         *
         */
        public ChainFuture() {
            // No-op.
        }

        /**
         * @param fut Future.
         * @param doneCb Closure.
         */
        ChainFuture(
            GridFutureAdapter<R> fut,
            IgniteClosure<? super IgniteInternalFuture<R>, T> doneCb
        ) {
            super();

            this.fut = fut;
            this.doneCb = doneCb;

            fut.listenAsync(new GridFutureChainListener<>(this, doneCb));
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return "ChainFuture [orig=" + fut + ", doneCb=" + doneCb + ']';
        }
    }
}
