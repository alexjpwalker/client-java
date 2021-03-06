/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package grakn.client.rpc;

import grabl.tracing.client.GrablTracingThreadStatic;
import grabl.tracing.client.GrablTracingThreadStatic.ThreadTrace;
import grakn.client.exception.GraknClientException;
import grakn.protocol.session.SessionProto;
import grakn.protocol.session.SessionProto.Transaction;
import grakn.protocol.session.SessionServiceGrpc;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static grabl.tracing.client.GrablTracingThreadStatic.traceOnThread;


/**
 * Wrapper making transaction calls to the Grakn RPC Server - handles sending a stream of Transaction.Req and
 * receiving a stream of Transaction.Res.
 * A request is sent with the #send(Transaction.Req)} method, and you can block for a response with the
 * #receive() method.
 * {@code
 * try (Transceiver tx = Transceiver.create(stub) {
 * tx.send(openMessage);
 * Transaction.Res doneMessage = tx.receive().ok();
 * tx.send(commitMessage);
 * StatusRuntimeException validationError = tx.receive.error();
 * }
 * }
 */
public class Transceiver implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Transceiver.class);

    private final Lock lock = new ReentrantLock();

    private final StreamObserver<Transaction.Req> requestSender;
    private final ResponseListener responseListener;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private Transceiver(StreamObserver<Transaction.Req> requestSender, ResponseListener responseListener) {
        this.requestSender = requestSender;
        this.responseListener = responseListener;
    }

    public static Transceiver create(SessionServiceGrpc.SessionServiceStub stub) {
        ResponseListener responseListener = new ResponseListener();
        StreamObserver<Transaction.Req> requestSender = stub.transaction(responseListener);
        return new Transceiver(requestSender, responseListener);
    }

    /**
     * Send a request and return immediately.
         * This method is non-blocking - it returns immediately.
     */
    private void send(Transaction.Req request) {
        try (ThreadTrace trace = traceOnThread("request")) {
            if (responseListener.terminated.get()) {
                throw GraknClientException.connectionClosed();
            }
            LOG.trace("send:{}", request);
            requestSender.onNext(request);
        }
    }

    /**
     * Block until a response is returned.
     */
    private Response receive() throws InterruptedException {
        try (ThreadTrace trace = traceOnThread("receive")) {
            Response response = responseListener.poll();
            LOG.trace("receive:{}", response);
            if (response.type() != Response.Type.OK) {
                close();
            }
            return response;
        }
    }

    public Response sendAndReceive(Transaction.Req request) throws InterruptedException {
        try {
            lock.lock();
            send(request);
            return receive();
        } finally {
            lock.unlock();
        }
    }

    public void sendAndReceiveAsync(Transaction.Req request, ResponseReceiver receiver) {
        try (ThreadTrace parentTrace = GrablTracingThreadStatic.traceOnThread("asyncQueue")) {
            executorService.execute(() -> {
                lock.lock();
                try (ThreadTrace ignored = parentTrace.traceOnThread("asyncExecute")) {
                    send(request);
                    while (true) {
                        Response res = receive();
                        if (!receiver.onResponse(res)) {
                            break;
                        }
                    }
                } catch (Exception ex) {
                    receiver.onResponse(Response.error(ex));
                } finally {
                    lock.unlock();
                }
            });
        }
    }

    public interface ResponseReceiver {
        boolean onResponse(Response response);
    }

    @Override
    public void close() {
        try {
            executorService.shutdownNow();
            requestSender.onCompleted();
            responseListener.onCompleted();
        } catch (IllegalStateException e) {
            //IGNORED
            //This is needed to handle the fact that:
            //1. Commits can lead to transaction closures and
            //2. Error can lead to connection closures but the transaction may stay open
            //When this occurs a "half-closed" state is thrown which we can safely ignore
        }
    }

    public boolean isOpen() {
        return !responseListener.terminated.get();
    }

    /**
     * A StreamObserver that stores all responses in a blocking queue.
         * A response can be polled with the #poll() method.
     */
    private static class ResponseListener implements StreamObserver<Transaction.Res> {

        private final BlockingQueue<Response> queue = new LinkedBlockingDeque<>();
        private final AtomicBoolean terminated = new AtomicBoolean(false);

        @Override
        public void onNext(Transaction.Res value) {
            queue.add(Response.ok(value));
        }

        @Override
        public void onError(Throwable throwable) {
            terminated.set(true);
            assert throwable instanceof StatusRuntimeException : "The server only yields these exceptions";
            queue.add(Response.error((StatusRuntimeException) throwable));
        }

        @Override
        public void onCompleted() {
            terminated.set(true);
            queue.add(Response.completed());
        }

        Response poll() throws InterruptedException {
            // First check for a response without blocking
            Response response = queue.poll();

            if (response != null) {
                return response;
            }

            // Only after checking for existing messages, we check if the connection was already terminated, so we don't
            // block for a response forever
            if (terminated.get()) {
                throw GraknClientException.connectionClosed();
            }

            // Block for a response (because we are confident there are no responses and the connection has not closed)
            return queue.take();
        }
    }

    /**
     * A response from the gRPC server, that may be a successful response #ok(Transaction.Res), an error
     * {#error(StatusRuntimeException)} or a "completed" message #completed().
     */
    public static class Response {

        private final SessionProto.Transaction.Res nullableOk;
        private final Exception nullableError;

        Response(@Nullable SessionProto.Transaction.Res nullableOk, @Nullable Exception nullableError) {
            this.nullableOk = nullableOk;
            this.nullableError = nullableError;
        }

        private static Response create(@Nullable Transaction.Res response, @Nullable Exception error) {
            if (!(response == null || error == null)) {
                throw new IllegalArgumentException("One of Transaction.Res or StatusRuntimeException must be null");
            }
            return new Response(response, error);
        }

        static Response completed() {
            return create(null, null);
        }

        static Response error(Exception error) {
            return create(null, error);
        }

        static Response ok(Transaction.Res response) {
            return create(response, null);
        }

        @Nullable
        SessionProto.Transaction.Res nullableOk() {
            return nullableOk;
        }

        @Nullable
        Exception nullableError() {
            return nullableError;
        }

        public final Type type() {
            if (nullableOk() != null) {
                return Type.OK;
            } else if (nullableError() != null) {
                return Type.ERROR;
            } else {
                return Type.COMPLETED;
            }
        }

        /**
         * If this is a successful response, retrieve it.
         *
         * @throws IllegalStateException if this is not a successful response
         */
        public final Transaction.Res ok() {
            Transaction.Res response = nullableOk();
            if (response == null) {
                throw new IllegalStateException("Expected successful response not found: " + toString());
            } else {
                return response;
            }
        }

        /**
         * If this is an error, retrieve it.
         *
         * @throws IllegalStateException if this is not an error
         */
        public final Exception error() {
            Exception throwable = nullableError();
            if (throwable == null) {
                throw new IllegalStateException("Expected error not found: " + toString());
            } else {
                return throwable;
            }
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "{nullableOk=" + nullableOk + ", nullableError=" + nullableError + "}";
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            if (o instanceof Transceiver.Response) {
                Transceiver.Response that = (Transceiver.Response) o;
                return ((this.nullableOk == null) ? (that.nullableOk() == null) : this.nullableOk.equals(that.nullableOk()))
                        && ((this.nullableError == null) ? (that.nullableError() == null) : this.nullableError.equals(that.nullableError()));
            }
            return false;
        }

        @Override
        public int hashCode() {
            int h = 1;
            h *= 1000003;
            h ^= (nullableOk == null) ? 0 : this.nullableOk.hashCode();
            h *= 1000003;
            h ^= (nullableError == null) ? 0 : this.nullableError.hashCode();
            return h;
        }

        /**
         * Enum indicating the type of Response.
         */
        public enum Type {
            OK, ERROR, COMPLETED
        }
    }
}
