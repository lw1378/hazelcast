/*
 * Copyright (c) 2008-2015, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.nio.tcp;

import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.ConnectionType;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.logging.Level;

public abstract class AbstractSelectionHandler implements MigratableHandler {

    protected final ILogger logger;
    protected final SocketChannelWrapper socketChannel;
    protected final TcpIpConnection connection;
    protected final TcpIpConnectionManager connectionManager;
    protected Selector selector;
    protected IOSelector ioSelector;

    private SelectionKey selectionKey;
    private final int initialOps;

    public AbstractSelectionHandler(TcpIpConnection connection, IOSelector ioSelector, int initialOps) {
        this.connection = connection;
        this.ioSelector = ioSelector;
        this.selector = ioSelector.getSelector();
        this.socketChannel = connection.getSocketChannelWrapper();
        this.connectionManager = connection.getConnectionManager();
        this.logger = connectionManager.ioService.getLogger(this.getClass().getName());
        this.initialOps = initialOps;
    }

    /**
     * Migrates this handler to a new IOSelector thread.
     * The migration logic is rather simple:
     * <p><ul>
     *     <li>Submit a de-registration task to a current IOSelector thread</li>
     *     <li>The de-registration task submits a registration task to the new IOSelector thread</li>
     * </ul></p>
     *
     * @param newOwner target IOSelector this handler migrates to
     */
    public void migrate(final IOSelector newOwner) {
        if (ioSelector == newOwner || !socketChannel.isOpen()) {
            return;
        }

        ioSelector.addTask(new MigrationTask(newOwner));
        ioSelector.wakeup();
    }

    protected SelectionKey getSelectionKey() {
        if (selectionKey == null) {
            try {
                selectionKey = socketChannel.register(selector, initialOps, this);
            } catch (ClosedChannelException e) {
                handleSocketException(e);
            }
        }
        return selectionKey;
    }

    final void handleSocketException(Throwable e) {
        if (e instanceof OutOfMemoryError) {
            connectionManager.ioService.onOutOfMemory((OutOfMemoryError) e);
        }
        if (selectionKey != null) {
            selectionKey.cancel();
        }
        connection.close(e);
        ConnectionType connectionType = connection.getType();
        if (connectionType.isClient() && !connectionType.isBinary()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(Thread.currentThread().getName());
        sb.append(" Closing socket to endpoint ");
        sb.append(connection.getEndPoint());
        sb.append(", Cause:").append(e);
        Level level = connectionManager.ioService.isActive() ? Level.WARNING : Level.FINEST;
        if (e instanceof IOException) {
            logger.log(level, sb.toString());
        } else {
            logger.log(level, sb.toString(), e);
        }
    }

    final void registerOp(int operation) {
        SelectionKey selectionKey = getSelectionKey();

        try {
            selectionKey.interestOps(selectionKey.interestOps() | operation);
        } catch (Throwable e) {
            handleSocketException(e);
        }
    }

    final void unregisterOp(int operation) {
        SelectionKey selectionKey = getSelectionKey();
        try {
            selectionKey.interestOps(selectionKey.interestOps() & ~operation);
        } catch (Throwable e) {
            handleSocketException(e);
        }
    }

    /**
     * {@inheritDoc}
     *
     */
    public IOSelector getOwner() {
        return ioSelector;
    }

    private class MigrationTask implements Runnable {
        private final IOSelector newOwner;

        public MigrationTask(IOSelector newOwner) {
            this.newOwner = newOwner;
        }

        @Override
        public void run() {
            if (!socketChannel.isOpen()) {
                return;
            }
            unregisterOp(initialOps);
            ioSelector = newOwner;
            selectionKey.cancel();
            selectionKey = null;
            newOwner.addTask(new Runnable() {
                @Override
                public void run() {
                    if (!socketChannel.isOpen()) {
                        return;
                    }
                    selector = newOwner.getSelector();
                    selectionKey = getSelectionKey();
                    registerOp(initialOps);
                }
            });
            newOwner.wakeup();
        }
    }
}
