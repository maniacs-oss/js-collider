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

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.logging.Level;
import java.util.logging.Logger;


public class ThreadPool
{
    private static class Sync extends AbstractQueuedSynchronizer
    {
        private int m_maxState;

        public Sync( int maxState )
        {
            m_maxState = maxState;
        }

        protected final int tryAcquireShared( int acquires )
        {
            for (;;)
            {
                int state = getState();
                int newState = (state - 1);
                if ((newState < 0) || compareAndSetState(state, newState))
                    return newState;
            }
        }

        protected final boolean tryReleaseShared( int releases )
        {
            for (;;)
            {
                int state = getState();
                if (state == m_maxState)
                    return true;
                int newState = (state + releases);
                if (compareAndSetState(state, newState))
                    return true;
            }
        }
    }

    private static class Queue
    {
        private final AtomicReference<Collider.ThreadPoolRunnable> m_head;
        private final AtomicReference<Collider.ThreadPoolRunnable> m_tail;

        public Queue()
        {
            m_head = new AtomicReference<Collider.ThreadPoolRunnable>();
            m_tail = new AtomicReference<Collider.ThreadPoolRunnable>();
        }

        public final Collider.ThreadPoolRunnable get()
        {
            Collider.ThreadPoolRunnable head = m_head.get();
            for (;;)
            {
                if (head == null)
                    return null;

                Collider.ThreadPoolRunnable next = head.nextThreadPoolRunnable;
                if (m_head.compareAndSet(head, next))
                {
                    if (next == null)
                    {
                        if (!m_tail.compareAndSet(head, null))
                        {
                            while (head.nextThreadPoolRunnable == null);
                            m_head.set( head.nextThreadPoolRunnable );
                        }
                    }
                    head.nextThreadPoolRunnable = null;
                    return head;
                }
                head = m_head.get();
            }
        }

        public final void put( Collider.ThreadPoolRunnable runnable )
        {
            Collider.ThreadPoolRunnable tail = m_tail.getAndSet( runnable );
            if (tail == null)
                m_head.set( runnable );
            else
                tail.nextThreadPoolRunnable = runnable;
        }
    }

    private static class Tls
    {
        private int m_idx;

        public Tls()
        {
            m_idx = 0;
        }

        public final int getNext()
        {
            if (++m_idx < 0)
                m_idx = 0;
            return m_idx;
        }
    }

    private class Worker extends Thread
    {
        public Worker( String name )
        {
            super( name );
        }

        public void run()
        {
            if (s_logger.isLoggable(Level.FINE))
                s_logger.fine( Thread.currentThread().getName() + ": started." );

            int queueIdx = 0;
            while (m_run)
            {
                m_sync.acquireShared(1);
                int cc = m_contentionFactor;
                for (;;)
                {
                    Collider.ThreadPoolRunnable runnable = m_queue[queueIdx].get();
                    if (runnable == null)
                    {
                        if (--cc == 0)
                            break;
                    }
                    else
                    {
                        runnable.runInThreadPool();
                        cc = m_contentionFactor;
                    }
                    queueIdx++;
                    queueIdx %= m_contentionFactor;
                }
            }

            if (s_logger.isLoggable(Level.FINE))
                s_logger.fine( Thread.currentThread().getName() + ": finished." );
        }
    }

    private static final Logger s_logger = Logger.getLogger( ThreadPool.class.getName() );

    private final int m_contentionFactor;
    private final Sync m_sync;
    private final Thread [] m_thread;
    private final Queue [] m_queue;
    private final ThreadLocal<Tls> m_tls;
    private volatile boolean m_run;

    public ThreadPool( String name, int threads, int contentionFactor )
    {
        m_contentionFactor = contentionFactor;
        m_sync = new Sync( threads );

        m_thread = new Thread[threads];
        for (int idx=0; idx<threads; idx++)
        {
            String workerName = (name + "-" + idx);
            m_thread[idx] = new Worker( workerName );
        }

        m_queue = new Queue[contentionFactor];
        for (int idx=0; idx<contentionFactor; idx++)
            m_queue[idx] = new Queue();

        m_tls = new ThreadLocal<Tls>() { protected Tls initialValue() { return new Tls(); } };
        m_run = true;
    }

    public ThreadPool( String name, int threads )
    {
        this( name, threads, 8 );
    }

    public final void start()
    {
        for (Thread thread : m_thread)
            thread.start();
    }

    public final void stopAndWait() throws InterruptedException
    {
        assert( m_thread != null );

        m_run = false;
        m_sync.releaseShared( m_thread.length );

        for (int idx=0; idx<m_thread.length; idx++)
        {
            if (m_thread[idx] != null)
            {
                m_thread[idx].join();
                m_thread[idx] = null;
            }
        }
    }

    public void execute( Collider.ThreadPoolRunnable runnable )
    {
        assert( runnable.nextThreadPoolRunnable == null );
        int idx = (m_tls.get().getNext() % m_contentionFactor);
        m_queue[idx].put( runnable );
        m_sync.releaseShared(1);
    }
}