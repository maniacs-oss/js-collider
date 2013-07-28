/*
 * JS-Collider framework.
 * Copyright (C) 2013 Sergey Zubarev
 * info@js-labs.org
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.jsl.collider;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;


public class InputQueue extends Collider.SelectorThreadRunnable implements Runnable
{
    private static class DataBlock
    {
        public DataBlock next;
        public ByteBuffer buf;
        public ByteBuffer rw;
        public ByteBuffer ww;

        public DataBlock( boolean useDirectBuffers, int blockSize )
        {
            next = null;
            if (useDirectBuffers)
                buf = ByteBuffer.allocateDirect( blockSize );
            else
                buf = ByteBuffer.allocate( blockSize );
            rw = buf.duplicate();
            ww = buf.duplicate();
            rw.limit(0);
        }
    }

    private static final ThreadLocal<DataBlock> s_tlsDataBlock = new ThreadLocal<DataBlock>();

    private Collider m_collider;
    private boolean m_useDirectBuffers;
    private int m_blockSize;
    private SocketChannel m_socketChannel;
    private SelectionKey m_selectionKey;
    private Session.Listener m_listener;

    private static final int LENGTH_MASK = 0x3FFFFFFF;
    private static final int CLOSED      = 0x40000000;
    private AtomicInteger m_length;
    private AtomicReference<DataBlock> m_dataBlock;

    private void readAndHandleData()
    {
        DataBlock dataBlock = s_tlsDataBlock.get();
        if (dataBlock == null)
            dataBlock = new DataBlock( m_useDirectBuffers, m_blockSize );
        else
            s_tlsDataBlock.remove();

        int bytesReceived = this.readData( dataBlock );
        if (bytesReceived > 0)
        {
            m_dataBlock.set( dataBlock );
            m_length.set( bytesReceived );
            m_collider.executeInSelectorThread( this );
            this.handleData( dataBlock, bytesReceived );
        }
        else
        {
            m_listener.onConnectionClosed();
            s_tlsDataBlock.set( dataBlock );
        }
    }

    private int readData( DataBlock dataBlock )
    {
        try
        {
            int bytesReceived = m_socketChannel.read( dataBlock.ww );
            if (bytesReceived > 0)
                return bytesReceived;
        }
        catch (NotYetConnectedException ignored) { }
        catch (IOException ignored) { }
        return -1;
    }

    private void handleData( DataBlock dataBlock, int bytesReady )
    {
        int length;
        ByteBuffer rw = dataBlock.rw;
        int limit = rw.limit();
        for (;;)
        {
            limit += bytesReady;
            rw.limit( limit );

            m_listener.onDataReceived( rw );
            rw.position( limit );

            length = m_length.addAndGet( -bytesReady );
            int bytesRest = (length & LENGTH_MASK);
            if (bytesRest == 0)
                break;

            if (rw.capacity() == rw.position())
            {
                DataBlock db = dataBlock.next;
                dataBlock.next = null;
                dataBlock.rw.clear();
                dataBlock.ww.clear();
                if (s_tlsDataBlock.get() == null)
                    s_tlsDataBlock.set( dataBlock );
                dataBlock = db;
                rw = dataBlock.rw;
                limit = 0;
            }
        }

        if ((length & CLOSED) == 0)
            m_listener.onConnectionClosed();

        if (m_dataBlock.compareAndSet(dataBlock, null))
        {
            if (s_tlsDataBlock.get() == null)
                s_tlsDataBlock.set( dataBlock );
        }
    }

    public InputQueue(
            Collider collider,
            int blockSize,
            SocketChannel socketChannel,
            SelectionKey selectionKey )
    {
        m_collider = collider;
        m_useDirectBuffers = collider.getConfig().useDirectBuffers;
        m_blockSize = blockSize;
        m_socketChannel = socketChannel;
        m_selectionKey = selectionKey;
        m_length = new AtomicInteger();
        m_dataBlock = new AtomicReference<DataBlock>();
    }

    public void setListenerAndStart( Session.Listener listener )
    {
        m_listener = listener;
        m_collider.executeInSelectorThread( this );
    }

    public void runInSelectorThread()
    {
        int interestOps = m_selectionKey.interestOps();
        assert( (interestOps & SelectionKey.OP_READ) == 0 );
        m_selectionKey.interestOps( interestOps | SelectionKey.OP_READ );
    }

    public void run()
    {
        DataBlock dataBlock = m_dataBlock.get();

        int length = m_length.get();
        if (length == 0)
        {
            this.readAndHandleData();
            return;
        }

        DataBlock prev = null;
        if (dataBlock.ww.remaining() == 0)
        {
            prev = dataBlock;
            dataBlock = s_tlsDataBlock.get();
            if (dataBlock == null)
                dataBlock = new DataBlock( m_useDirectBuffers, m_blockSize );
            else
                s_tlsDataBlock.remove();
        }

        int bytesReceived = this.readData( dataBlock );
        if (bytesReceived > 0)
        {
            if (prev != null)
                prev.next = dataBlock;

            for (;;)
            {
                int newLength = (length & LENGTH_MASK) + bytesReceived;
                /*
                if ((newLength & LENGTH_MASK) > LENGTH_MASK)
                    Queue is too big !!!
                */
                if (m_length.compareAndSet(length, newLength))
                {
                    length = newLength;
                    break;
                }
                length = m_length.get();
            }

            if (length == bytesReceived)
            {
                if (prev != null)
                {
                    prev.next = null;
                    if (s_tlsDataBlock.get() == null)
                        s_tlsDataBlock.set( prev );
                    m_dataBlock.set( dataBlock );
                }

                m_collider.executeInSelectorThread( this );
                this.handleData( dataBlock, bytesReceived );
            }
            else
            {
                m_collider.executeInSelectorThread( this );
            }
        }
        else
        {
            for (;;)
            {
                int newLength = (length | CLOSED);
                if (m_length.compareAndSet(length, newLength))
                {
                    length = newLength;
                    break;
                }
                length = m_length.get();
            }

            if ((length & LENGTH_MASK) == 0)
            {
                m_listener.onConnectionClosed();
                if (prev != null)
                {
                    if (s_tlsDataBlock.get() == null)
                        s_tlsDataBlock.set( dataBlock );
                }
            }
        }
    }

    public void stop()
    {
    }
}