/*

Copyright (C) SYSTAP, LLC 2006-2008.  All rights reserved.

Contact:
     SYSTAP, LLC
     4501 Tower Road
     Greensboro, NC 27410
     licenses@bigdata.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
/*
 * Created on Jan 7, 2009
 */

package com.bigdata.zookeeper;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;

/**
 * An abstract implementation based on synchronized(this) and
 * {@link Object#notify()}.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
abstract public class AbstractZNodeConditionWatcher implements Watcher {

    final static protected Logger log = Logger.getLogger(AbstractZNodeConditionWatcher.class);

    final static protected boolean INFO = log.isInfoEnabled();

    final static protected boolean DEBUG = log.isDebugEnabled();

    protected final ZooKeeper zookeeper;
    
    /**
     * 
     * @param zookeeper
     * @param zpath
     *            The path that is being watched.
     */
    protected AbstractZNodeConditionWatcher(final ZooKeeper zookeeper,
            final String zpath) {

        if (zookeeper == null)
            throw new IllegalArgumentException();

        if (zpath == null)
            throw new IllegalArgumentException();

        this.zookeeper = zookeeper;

        this.zpath = zpath;

    }
    
    private volatile boolean disconnected = false;

    private volatile boolean conditionSatisified = false;

    /**
     * The zpath that is being watched.
     */
    protected final String zpath;

    /**
     * Return a representation of the watcher state (non-blocking).
     * <p>
     * Note: The implementation MUST be safe and non-blocking.
     */
    public String toString() {

        final StringBuilder sb = new StringBuilder();

        sb.append(getClass().getSimpleName());
        sb.append("{ zpath=" + zpath);
        sb.append(", conditionSatisified=" + conditionSatisified);
        sb.append(", disconnected=" + disconnected);
        toString(sb); // extension hook
        sb.append("}");

        return sb.toString();
        
    }
    
    /**
     * Subclasses may extend this method to add additional state into the
     * representation generated by {@link #toString()}.
     * <p>
     * Note: This implementation MUST be safe (no exceptions) and non-blocking.
     * 
     * @param sb
     */
    protected void toString(StringBuilder sb) {
        
    }
    
    /**
     * Clear the watch. This is necessary for the {@link Watcher} to stop
     * getting notices of changes after it has noticed the change that it was
     * looking for.
     */
    final private void _clearWatches() {
        
        try {

            if (INFO)
                log.info("Clearing watch: " + this);
        
            clearWatch();
            
        } catch (KeeperException ex) {
            
            // ignore
            log.warn(ex);
            
        } catch (InterruptedException ex) {
            
            // ignore.
            log.warn(ex);
            
        }

    }

    /**
     * Notify a {@link Thread} synchronized on itself when the znode that it is
     * watching generates an {@link WatchedEvent}. If the event is a
     * disconnect, then we instead set the {@link #disconnected} flag and return
     * immediately.
     */
    public void process(final WatchedEvent event) {

        if(INFO)
            log.info(event.toString());
        
        synchronized (this) {

            switch(event.getState()) {
            case Disconnected:
                // nothing to do until we are reconnected.
                disconnected = true;
                return;
            default:
                if (disconnected) {
                    _resumeWatch();
                }
                // fall through
                break;
            }
            
            boolean satisifed;
            try {
                satisifed = isConditionSatisified(event);
            } catch (KeeperException e) {
                log.warn(this.toString(), e);
                return;
            } catch (InterruptedException e) {
                log.warn(this.toString(), e);
                return;
            }

            if (satisifed) {

                success(event.getType().toString());
                
                return;

            } else {
                
                _resumeWatch();
                
            }

        }

    }

    /**
     * Implementation must inspect the event and determine if the conditions are
     * satisified.
     * 
     * @param event
     *            The {@link WatchedEvent}
     * 
     * @return <code>true</code> if the event satisified the condition.
     * 
     * @throws KeeperException
     * @throws InterruptedException
     */
    abstract protected boolean isConditionSatisified(WatchedEvent event)
            throws KeeperException, InterruptedException;
     
    /**
     * Implementation must check the state of the znode using the {@link #zpath}
     * and determine if the conditions are satisified <em>always</em>
     * resetting the watch(es) as a side-effect.
     * <p>
     * This is used to handle the initial case, where we need to know whether or
     * not the condition is satisified before waiting for an event.
     * 
     * @return
     * 
     * @throws KeeperException
     * @throws InterruptedException
     */
    abstract protected boolean isConditionSatisified() throws KeeperException,
            InterruptedException;

    /**
     * Clear any watches.
     * 
     * @throws KeeperException
     * @throws InterruptedException
     */
    abstract protected void clearWatch() throws KeeperException,
            InterruptedException;
        
    /**
     * Resumes watching the zpath. However, if the condition is satisified then
     * we report {@link #success(String)} and clear the watch.
     */
    protected void _resumeWatch() {

        try {

            if (INFO)
                log.info("will reset watch");

            // reset the watch.
            if (isConditionSatisified()) {

                // in case we were disconnected.
                disconnected = false;

                // node already exists.
                success("already exists");

            }

            // in case we were disconnected.
            disconnected = false;

            if (INFO)
                log.info("did reset watch");
            
        } catch (Throwable t) {

            log.warn("Could not reset the watch: " + this, t);

        }

    }

    /**
     * Caller must be synchronized on <i>this</i>.
     */
    protected void success(final String msg) {

        conditionSatisified = true;

        if(INFO)
            log.info(msg + " : " + this);

        this.notify();
        
        // clear watch or we will keep getting notices.
        _clearWatches();

    }
    
    /**
     * This implementation always returns <code>false</code> but may be
     * overriden to permit cancellation of
     * {@link #awaitCondition(long, TimeUnit)}.
     * 
     * @return
     */
    protected boolean isCancelled() {
        
        return false;
        
    }
    
    /**
     * Wait up to timeout units for the watched znode to be created.
     * <p>
     * An instance of this watcher is set on a <strong>single</strong> znode.
     * The caller then {@link Object#wait()}s on the watcher until the watcher
     * notifies itself. When the caller wakes up it checks the time remaining
     * and whether or not the condition has been satisified. If the timeout has
     * noticeably expired then it returns false. If the condition has been
     * satisified and the timeout has not expired it returns true. Otherwise we
     * continue to wait.
     * <p>
     * The {@link Thread} MUST test {@link #conditionSatisified} while holding
     * the lock and before waiting (in case the event has already occurred), and
     * again each time {@link Object#wait()} returns (since wait and friends MAY
     * return spuriously). The watch will be re-established until the timeout
     * has elapsed or the condition has been satisified, at which point the
     * watch is explicitly cleared before returning to the caller.
     * <p>
     * This pattern should be robust in the face of a service disconnect. When a
     * reconnect {@link WatchedEvent} is received, it will test the condition
     * and then reset or clear its watch as necessary.
     * <p>
     * Note: the resolution is millseconds at most.
     * 
     * @param timeout
     *            The timeout.
     * @param unit
     *            The units.
     * 
     * @return <code>false</code> if the waiting time detectably elapsed
     *         before return from the method, else <code>true</code>.
     * 
     * @throws TimeoutException
     * @throws InterruptedException
     */
    public boolean awaitCondition(final long timeout, final TimeUnit unit)
    throws InterruptedException {
        
        return awaitCondition(true/* testConditionOnEntry */, timeout, unit);
        
    }
    
    /**
     * 
     * @param testConditionOnEntry
     * @param timeout
     * @param unit
     * @return
     * @throws InterruptedException
     */
    public boolean awaitCondition(final boolean testConditionOnEntry,
            final long timeout, final TimeUnit unit)
            throws InterruptedException {

        final long begin = System.currentTimeMillis();

        long millis = unit.toMillis(timeout);

        synchronized (this) {

            if (testConditionOnEntry) {

                try {

                    if (isConditionSatisified()) {

                        // condition was satisified before waiting.

                        success("on entry.");

                        return true;

                    }

                } catch (KeeperException ex) {

                    log.warn("On entry: " + ex, ex);

                    /*
                     * Fall through.
                     * 
                     * Note: by falling through we handle the case where the
                     * client was not connected to a server when the caller made
                     * their request or where a node does not yet exist, etc.
                     */

                }
            
            }

            while (millis > 0 && !conditionSatisified && !isCancelled()) {

                this.wait(millis);

                millis -= (System.currentTimeMillis() - begin);

                if (INFO)
                    log.info("woke up: conditionSatisifed="
                            + conditionSatisified + ", remaining=" + millis
                            + "ms");
                
            }
            
            if(isCancelled()) {
                
                throw new InterruptedException();
                
            }

            return millis > 0;
            
        }
        
    }

}
