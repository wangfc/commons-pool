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
package org.apache.commons.pool2.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.pool2.KeyedObjectPool;
import org.apache.commons.pool2.KeyedPoolableObjectFactory;
import org.apache.commons.pool2.PoolUtils;

/**
 * A configurable <code>KeyedObjectPool</code> implementation.
 * <p>
 * When coupled with the appropriate {@link KeyedPoolableObjectFactory},
 * <code>GenericKeyedObjectPool</code> provides robust pooling functionality for
 * keyed objects. A <code>GenericKeyedObjectPool</code> can be viewed as a map
 * of sub-pools, keyed on the (unique) key values provided to the
 * {@link #preparePool preparePool}, {@link #addObject addObject} or
 * {@link #borrowObject borrowObject} methods. Each time a new key value is
 * provided to one of these methods, a sub-new pool is created under the given
 * key to be managed by the containing <code>GenericKeyedObjectPool.</code>
 * <p>
 * Optionally, one may configure the pool to examine and possibly evict objects
 * as they sit idle in the pool and to ensure that a minimum number of idle
 * objects is maintained for each key. This is performed by an "idle object
 * eviction" thread, which runs asynchronously. Caution should be used when
 * configuring this optional feature. Eviction runs contend with client threads
 * for access to objects in the pool, so if they run too frequently performance
 * issues may result.
 * <p>
 * Implementation note: To prevent possible deadlocks, care has been taken to
 * ensure that no call to a factory method will occur within a synchronization
 * block. See POOL-125 and DBCP-44 for more information.
 * <p>
 * This class is intended to be thread-safe.
 *
 * @see GenericObjectPool
 *
 * @param <K> The type of keys maintained by this pool.
 * @param <T> Type of element pooled in this pool.
 *
 * @author Rodney Waldhoff
 * @author Dirk Verbeeck
 * @author Sandy McArthur
 *
 * @version $Revision$
 */
public class GenericKeyedObjectPool<K,T> extends BaseGenericObjectPool<T>
        implements KeyedObjectPool<K,T>, GenericKeyedObjectPoolMBean<K> {

    /**
     * Create a new <code>GenericKeyedObjectPool</code> using defaults from
     * {@link GenericKeyedObjectPoolConfig}.
     */
    public GenericKeyedObjectPool(KeyedPoolableObjectFactory<K,T> factory) {
        this(factory, new GenericKeyedObjectPoolConfig());
    }

    /**
     * Create a new <code>GenericKeyedObjectPool</code> using a specific
     * configuration.
     *
     * @param config    The configuration to use for this pool instance. The
     *                  configuration is used by value. Subsequent changes to
     *                  the configuration object will not be reflected in the
     *                  pool.
     */
    public GenericKeyedObjectPool(KeyedPoolableObjectFactory<K,T> factory,
            GenericKeyedObjectPoolConfig config) {

        super(config, ONAME_BASE, config.getJmxNamePrefix());

        if (factory == null) {
            throw new IllegalArgumentException("factory may not be null");
        }
        this.factory = factory;

        setConfig(config);

        startEvictor(getMinEvictableIdleTimeMillis());
    }

    /**
     * Returns the limit on the number of object instances allocated by the pool
     * (checked out or idle), per key. When the limit is reached, the sub-pool
     * is said to be exhausted. A negative value indicates no limit.
     *
     * @return the limit on the number of active instances per key
     *
     * @see #setMaxTotalPerKey
     */
    @Override
    public int getMaxTotalPerKey() {
        return maxTotalPerKey;
    }

    /**
     * Sets the limit on the number of object instances allocated by the pool
     * (checked out or idle), per key. When the limit is reached, the sub-pool
     * is said to be exhausted. A negative value indicates no limit.
     *
     * @param maxTotalPerKey the limit on the number of active instances per key
     *
     * @see #getMaxTotalPerKey
     */
    public void setMaxTotalPerKey(int maxTotalPerKey) {
        this.maxTotalPerKey = maxTotalPerKey;
    }


    /**
     * Returns the cap on the number of "idle" instances per key in the pool.
     * If maxIdlePerKey is set too low on heavily loaded systems it is possible
     * you will see objects being destroyed and almost immediately new objects
     * being created. This is a result of the active threads momentarily
     * returning objects faster than they are requesting them them, causing the
     * number of idle objects to rise above maxIdlePerKey. The best value for
     * maxIdlePerKey for heavily loaded system will vary but the default is a
     * good starting point.
     *
     * @return the maximum number of "idle" instances that can be held in a
     *         given keyed sub-pool or a negative value if there is no limit
     *
     * @see #setMaxIdlePerKey
     */
    @Override
    public int getMaxIdlePerKey() {
        return maxIdlePerKey;
    }

    /**
     * Sets the cap on the number of "idle" instances per key in the pool.
     * If maxIdlePerKey is set too low on heavily loaded systems it is possible
     * you will see objects being destroyed and almost immediately new objects
     * being created. This is a result of the active threads momentarily
     * returning objects faster than they are requesting them them, causing the
     * number of idle objects to rise above maxIdlePerKey. The best value for
     * maxIdlePerKey for heavily loaded system will vary but the default is a
     * good starting point.
     *
     * @param maxIdlePerKey the maximum number of "idle" instances that can be
     *                      held in a given keyed sub-pool. Use a negative value
     *                      for no limit
     *
     * @see #getMaxIdlePerKey
     */
    public void setMaxIdlePerKey(int maxIdlePerKey) {
        this.maxIdlePerKey = maxIdlePerKey;
    }

    /**
     * Sets the target for the minimum number of idle objects to maintain in
     * each of the keyed sub-pools. This setting only has an effect if it is
     * positive and {@link #getTimeBetweenEvictionRunsMillis()} is greater than
     * zero. If this is the case, an attempt is made to ensure that each
     * sub-pool has the required minimum number of instances during idle object
     * eviction runs.
     * <p>
     * If the configured value of minIdlePerKey is greater than the configured
     * value for maxIdlePerKey then the value of maxIdlePerKey will be used
     * instead.
     *
     * @param minIdlePerKey The minimum size of the each keyed pool
     *
     * @see #getMinIdlePerKey
     * @see #getMaxIdlePerKey()
     * @see #setTimeBetweenEvictionRunsMillis
     */
    public void setMinIdlePerKey(int minIdlePerKey) {
        this.minIdlePerKey = minIdlePerKey;
    }

    /**
     * Returns the target for the minimum number of idle objects to maintain in
     * each of the keyed sub-pools. This setting only has an effect if it is
     * positive and {@link #getTimeBetweenEvictionRunsMillis()} is greater than
     * zero. If this is the case, an attempt is made to ensure that each
     * sub-pool has the required minimum number of instances during idle object
     * eviction runs.
     * <p>
     * If the configured value of minIdlePerKey is greater than the configured
     * value for maxIdlePerKey then the value of maxIdlePerKey will be used
     * instead.
     *
     * @return minimum size of the each keyed pool
     *
     * @see #setTimeBetweenEvictionRunsMillis
     */
    @Override
    public int getMinIdlePerKey() {
        int maxIdlePerKey = getMaxIdlePerKey();
        if (this.minIdlePerKey > maxIdlePerKey) {
            return maxIdlePerKey;
        } else {
            return minIdlePerKey;
        }
    }

    /**
     * Sets the configuration.
     *
     * @param conf the new configuration to use. This is used by value.
     *
     * @see GenericKeyedObjectPoolConfig
     */
    public void setConfig(GenericKeyedObjectPoolConfig conf) {
        setLifo(conf.getLifo());
        setMaxIdlePerKey(conf.getMaxIdlePerKey());
        setMaxTotalPerKey(conf.getMaxTotalPerKey());
        setMaxTotal(conf.getMaxTotal());
        setMinIdlePerKey(conf.getMinIdlePerKey());
        setMaxWaitMillis(conf.getMaxWaitMillis());
        setBlockWhenExhausted(conf.getBlockWhenExhausted());
        setTestOnBorrow(conf.getTestOnBorrow());
        setTestOnReturn(conf.getTestOnReturn());
        setTestWhileIdle(conf.getTestWhileIdle());
        setNumTestsPerEvictionRun(conf.getNumTestsPerEvictionRun());
        setMinEvictableIdleTimeMillis(conf.getMinEvictableIdleTimeMillis());
        setSoftMinEvictableIdleTimeMillis(
                conf.getSoftMinEvictableIdleTimeMillis());
        setTimeBetweenEvictionRunsMillis(
                conf.getTimeBetweenEvictionRunsMillis());
        setEvictionPolicyClassName(conf.getEvictionPolicyClassName());
    }

    /**
     * Obtain a reference to the factory used to create, destroy and validate
     * the objects used by this pool.
     *
     * @return the factory
     */
    public KeyedPoolableObjectFactory<K, T> getFactory() {
        return factory;
    }

    /**
     * Equivalent to <code>{@link #borrowObject(Object, long) borrowObject}(key,
     * {@link #getMaxWaitMillis()})</code>.
     */
    @Override
    public T borrowObject(K key) throws Exception {
        return borrowObject(key, getMaxWaitMillis());
    }

    /**
     * Borrows an object from the sub-pool associated with the given key using
     * the specified waiting time which only applies if
     * {@link #getBlockWhenExhausted()} is true.
     * <p>
     * If there is one or more idle instances available in the sub-pool
     * associated with the given key, then an idle instance will be selected
     * based on the value of {@link #getLifo()}, activated and returned.  If
     * activation fails, or {@link #getTestOnBorrow() testOnBorrow} is set to
     * <code>true</code> and validation fails, the instance is destroyed and the
     * next available instance is examined.  This continues until either a valid
     * instance is returned or there are no more idle instances available.
     * <p>
     * If there are no idle instances available in the sub-pool associated with
     * the given key, behavior depends on the {@link #getMaxTotalPerKey()
     * maxTotalPerKey}, {@link #getMaxTotal() maxTotal}, and (if applicable)
     * {@link #getBlockWhenExhausted()} and the value passed in to the
     * <code>borrowMaxWait</code> parameter. If the number of instances checked
     * out from the sub-pool under the given key is less than
     * <code>maxTotalPerKey</code> and the total number of instances in
     * circulation (under all keys) is less than <code>maxTotal</code>, a new
     * instance is created, activated and (if applicable) validated and returned
     * to the caller.
     * <p>
     * If the associated sub-pool is exhausted (no available idle instances and
     * no capacity to create new ones), this method will either block
     * ({@link #getBlockWhenExhausted()} is true) or throw a
     * <code>NoSuchElementException</code>
     * ({@link #getBlockWhenExhausted()} is false).
     * The length of time that this method will block when
     * {@link #getBlockWhenExhausted()} is true is determined by the value
     * passed in to the <code>borrowMaxWait</code> parameter.
     * <p>
     * When <code>maxTotal</code> is set to a positive value and this method is
     * invoked when at the limit with no idle instances available, an attempt is
     * made to create room by clearing the oldest 15% of the elements from the
     * keyed sub-pools.
     * <p>
     * When the pool is exhausted, multiple calling threads may be
     * simultaneously blocked waiting for instances to become available. A
     * "fairness" algorithm has been implemented to ensure that threads receive
     * available instances in request arrival order.
     *
     * @param key pool key
     * @param borrowMaxWaitMillis The time to wait in milliseconds for an object
     *                            to become available
     *
     * @return object instance from the keyed pool
     *
     * @throws NoSuchElementException if a keyed object instance cannot be
     *                                returned.
     */
    public T borrowObject(K key, long borrowMaxWaitMillis) throws Exception {
        assertOpen();

        PooledObject<T> p = null;

        // Get local copy of current config so it is consistent for entire
        // method execution
        boolean blockWhenExhausted = getBlockWhenExhausted();

        boolean create;
        long waitTime = 0;
        ObjectDeque<T> objectDeque = register(key);

        try {
            while (p == null) {
                create = false;
                if (blockWhenExhausted) {
                    if (objectDeque != null) {
                        p = objectDeque.getIdleObjects().pollFirst();
                    }
                    if (p == null) {
                        create = true;
                        p = create(key);
                    }
                    if (p == null && objectDeque != null) {
                        if (borrowMaxWaitMillis < 0) {
                            p = objectDeque.getIdleObjects().takeFirst();
                        } else {
                            waitTime = System.currentTimeMillis();
                            p = objectDeque.getIdleObjects().pollFirst(
                                    borrowMaxWaitMillis, TimeUnit.MILLISECONDS);
                            waitTime = System.currentTimeMillis() - waitTime;
                        }
                    }
                    if (p == null) {
                        throw new NoSuchElementException(
                                "Timeout waiting for idle object");
                    }
                    if (!p.allocate()) {
                        p = null;
                    }
                } else {
                    if (objectDeque != null) {
                        p = objectDeque.getIdleObjects().pollFirst();
                    }
                    if (p == null) {
                        create = true;
                        p = create(key);
                    }
                    if (p == null) {
                        throw new NoSuchElementException("Pool exhausted");
                    }
                    if (!p.allocate()) {
                        p = null;
                    }
                }

                if (p != null) {
                    try {
                        factory.activateObject(key, p.getObject());
                    } catch (Exception e) {
                        try {
                            destroy(key, p, true);
                        } catch (Exception e1) {
                            // Ignore - activation failure is more important
                        }
                        p = null;
                        if (create) {
                            NoSuchElementException nsee = new NoSuchElementException(
                                    "Unable to activate object");
                            nsee.initCause(e);
                            throw nsee;
                        }
                    }
                    if (p != null && getTestOnBorrow()) {
                        boolean validate = false;
                        Throwable validationThrowable = null;
                        try {
                            validate = factory.validateObject(key, p.getObject());
                        } catch (Throwable t) {
                            PoolUtils.checkRethrow(t);
                        }
                        if (!validate) {
                            try {
                                destroy(key, p, true);
                                destroyedByBorrowValidationCount.incrementAndGet();
                            } catch (Exception e) {
                                // Ignore - validation failure is more important
                            }
                            p = null;
                            if (create) {
                                NoSuchElementException nsee = new NoSuchElementException(
                                        "Unable to validate object");
                                nsee.initCause(validationThrowable);
                                throw nsee;
                            }
                        }
                    }
                }
            }
        } finally {
            deregister(key);
        }

        updateStatsBorrow(p, waitTime);

        return p.getObject();
    }


    /**
     * Returns an object to a keyed sub-pool.
     * <p>
     * If {@link #getMaxIdlePerKey() maxIdle} is set to a positive value and the
     * number of idle instances under the given key has reached this value, the
     * returning instance is destroyed.
     * <p>
     * If {@link #getTestOnReturn() testOnReturn} == true, the returning
     * instance is validated before being returned to the idle instance sub-pool
     * under the given key. In this case, if validation fails, the instance is
     * destroyed.
     * <p>
     * Exceptions encountered destroying objects for any reason are swallowed
     * but remain accessible via {@link #getSwallowedExceptions()}.
     *
     * @param key pool key
     * @param obj instance to return to the keyed pool
     *
     * @throws IllegalStateException if an object is returned to the pool that
     *                               was not borrowed from it or if an object is
     *                               returned to the pool multiple times
     */
    @Override
    public void returnObject(K key, T obj) {

        ObjectDeque<T> objectDeque = poolMap.get(key);

        PooledObject<T> p = objectDeque.getAllObjects().get(obj);

        if (p == null) {
            throw new IllegalStateException(
                    "Returned object not currently part of this pool");
        }

        long activeTime = p.getActiveTimeMillis();

        if (getTestOnReturn()) {
            if (!factory.validateObject(key, obj)) {
                try {
                    destroy(key, p, true);
                } catch (Exception e) {
                    swallowException(e);
                }
                updateStatsReturn(activeTime);
                return;
            }
        }

        try {
            factory.passivateObject(key, obj);
        } catch (Exception e1) {
            swallowException(e1);
            try {
                destroy(key, p, true);
            } catch (Exception e) {
                swallowException(e);
            }
            updateStatsReturn(activeTime);
            return;
        }

        if (!p.deallocate()) {
            throw new IllegalStateException(
                    "Object has already been retured to this pool");
        }

        int maxIdle = getMaxIdlePerKey();
        LinkedBlockingDeque<PooledObject<T>> idleObjects =
            objectDeque.getIdleObjects();

        if (isClosed() || maxIdle > -1 && maxIdle <= idleObjects.size()) {
            try {
                destroy(key, p, true);
            } catch (Exception e) {
                swallowException(e);
            }
        } else {
            if (getLifo()) {
                idleObjects.addFirst(p);
            } else {
                idleObjects.addLast(p);
            }
        }

        if (hasBorrowWaiters()) {
            reuseCapacity();
        }

        updateStatsReturn(activeTime);
    }


    /**
     * {@inheritDoc}
     * <p>
     * Activation of this method decrements the active count associated with
     * the given keyed pool and attempts to destroy <code>obj.</code>
     *
     * @param key pool key
     * @param obj instance to invalidate
     *
     * @throws Exception             if an exception occurs destroying the
     *                               object
     * @throws IllegalStateException if obj does not belong to the pool
     *                               under the given key
     */
    @Override
    public void invalidateObject(K key, T obj) throws Exception {

        ObjectDeque<T> objectDeque = poolMap.get(key);

        PooledObject<T> p = objectDeque.getAllObjects().get(obj);
        if (p == null) {
            throw new IllegalStateException(
                    "Object not currently part of this pool");
        }
        destroy(key, p, true);
    }


    /**
     * Clears any objects sitting idle in the pool by removing them from the
     * idle instance sub-pools and then invoking the configured
     * PoolableObjectFactory's
     * {@link KeyedPoolableObjectFactory#destroyObject(Object, Object)} method
     * on each idle instance.
     * <p>
     * Implementation notes:
     * <ul>
     * <li>This method does not destroy or effect in any way instances that are
     * checked out when it is invoked.</li>
     * <li>Invoking this method does not prevent objects being returned to the
     * idle instance pool, even during its execution. Additional instances may
     * be returned while removed items are being destroyed.</li>
     * <li>Exceptions encountered destroying idle instances are swallowed but
     * remain accessible via {@link #getSwallowedExceptions()}.</li>
     * </ul>
     */
    @Override
    public void clear() {
        Iterator<K> iter = poolMap.keySet().iterator();

        while (iter.hasNext()) {
            clear(iter.next());
        }
    }


    /**
     * Clears the specified sub-pool, removing all pooled instances
     * corresponding to the given <code>key</code>. Exceptions encountered
     * destroying idle instances are swallowed but remain accessible via
     * {@link #getSwallowedExceptions()}.
     *
     * @param key the key to clear
     */
    @Override
    public void clear(K key) {

        ObjectDeque<T> objectDeque = register(key);

        try {
            LinkedBlockingDeque<PooledObject<T>> idleObjects =
                    objectDeque.getIdleObjects();

            PooledObject<T> p = idleObjects.poll();

            while (p != null) {
                try {
                    destroy(key, p, true);
                } catch (Exception e) {
                    swallowException(e);
                }
                p = idleObjects.poll();
            }
        } finally {
            deregister(key);
        }
    }


    /**
     * Returns the total number of instances current borrowed from this pool but
     * not yet returned.
     */
    @Override
    public int getNumActive() {
        return numTotal.get() - getNumIdle();
    }

    @Override
    public int getNumIdle() {
        Iterator<ObjectDeque<T>> iter = poolMap.values().iterator();
        int result = 0;

        while (iter.hasNext()) {
            result += iter.next().getIdleObjects().size();
        }

        return result;
    }

    /**
     * Returns the number of instances currently borrowed from but not yet
     * returned to the sub-pool corresponding to the given <code>key</code>.
     *
     * @param key the key to query
     */
    @Override
    public int getNumActive(K key) {
        final ObjectDeque<T> objectDeque = poolMap.get(key);
        if (objectDeque != null) {
            return objectDeque.getAllObjects().size() -
                    objectDeque.getIdleObjects().size();
        } else {
            return 0;
        }
    }

    /**
     * Returns the number of idle instances in the sub-pool corresponding to the
     * given <code>key</code>.
     *
     * @param key the key to query
     */
    @Override
    public int getNumIdle(K key) {
        final ObjectDeque<T> objectDeque = poolMap.get(key);
        return objectDeque != null ? objectDeque.getIdleObjects().size() : 0;
    }


    /**
     * Closes the keyed object pool. Once the pool is closed,
     * {@link #borrowObject(Object)} will fail with IllegalStateException, but
     * {@link #returnObject(Object, Object)} and
     * {@link #invalidateObject(Object, Object)} will continue to work, with
     * returned objects destroyed on return.
     * <p>
     * Destroys idle instances in the pool by invoking {@link #clear()}.
     */
    @Override
    public void close() {
        if (isClosed()) {
            return;
        }

        synchronized (closeLock) {
            if (isClosed()) {
                return;
            }

            // Stop the evictor before the pool is closed since evict() calls
            // assertOpen()
            startEvictor(-1L);

            closed = true;
            // This clear removes any idle objects
            clear();

            jmxUnregister();

            // Release any threads that were waiting for an object
            Iterator<ObjectDeque<T>> iter = poolMap.values().iterator();
            while (iter.hasNext()) {
                iter.next().getIdleObjects().interuptTakeWaiters();
            }
            // This clear cleans up the keys now any waiting threads have been
            // interrupted
            clear();
        }
    }


    /**
     * Clears oldest 15% of objects in pool.  The method sorts the objects into
     * a TreeMap and then iterates the first 15% for removal.
     */
    public void clearOldest() {

        // build sorted map of idle objects
        final Map<PooledObject<T>, K> map = new TreeMap<PooledObject<T>, K>();

        for (K k : poolMap.keySet()) {
            ObjectDeque<T> queue = poolMap.get(k);
            // Protect against possible NPE if key has been removed in another
            // thread. Not worth locking the keys while this loop completes.
            if (queue != null) {
                final LinkedBlockingDeque<PooledObject<T>> idleObjects =
                    queue.getIdleObjects();
                for (PooledObject<T> p : idleObjects) {
                    // each item into the map using the PooledObject object as the
                    // key. It then gets sorted based on the idle time
                    map.put(p, k);
                }
            }
        }

        // Now iterate created map and kill the first 15% plus one to account
        // for zero
        int itemsToRemove = ((int) (map.size() * 0.15)) + 1;
        Iterator<Map.Entry<PooledObject<T>, K>> iter =
            map.entrySet().iterator();

        while (iter.hasNext() && itemsToRemove > 0) {
            Map.Entry<PooledObject<T>, K> entry = iter.next();
            // kind of backwards on naming.  In the map, each key is the
            // PooledObject because it has the ordering with the timestamp
            // value.  Each value that the key references is the key of the
            // list it belongs to.
            K key = entry.getValue();
            PooledObject<T> p = entry.getKey();
            // Assume the destruction succeeds
            boolean destroyed = true;
            try {
                destroyed = destroy(key, p, false);
            } catch (Exception e) {
                swallowException(e);
            }
            if (destroyed) {
                itemsToRemove--;
            }
        }
    }

    /**
     * Attempt to create one new instance to serve from the most heavily
     * loaded pool that can add a new instance.
     *
     * This method exists to ensure liveness in the pool when threads are
     * parked waiting and capacity to create instances under the requested keys
     * subsequently becomes available.
     *
     * This method is not guaranteed to create an instance and its selection
     * of the most loaded pool that can create an instance may not always be
     * correct, since it does not lock the pool and instances may be created,
     * borrowed, returned or destroyed by other threads while it is executing.
     */
    private void reuseCapacity() {
        final int maxTotalPerKey = getMaxTotalPerKey();

        // Find the most loaded pool that could take a new instance
        int maxQueueLength = 0;
        LinkedBlockingDeque<PooledObject<T>> mostLoaded = null;
        K loadedKey = null;
        for (K k : poolMap.keySet()) {
            final ObjectDeque<T> deque = poolMap.get(k);
            if (deque != null) {
                final LinkedBlockingDeque<PooledObject<T>> pool = deque.getIdleObjects();
                final int queueLength = pool.getTakeQueueLength();
                if (getNumActive(k) < maxTotalPerKey && queueLength > maxQueueLength) {
                    maxQueueLength = queueLength;
                    mostLoaded = pool;
                    loadedKey = k;
                }
            }
        }

        // Attempt to add an instance to the most loaded pool
        if (mostLoaded != null) {
            register(loadedKey);
            try {
                PooledObject<T> p = create(loadedKey);
                if (p != null) {
                    addIdleObject(loadedKey, p);
                }
            } catch (Exception e) {
                swallowException(e);
            } finally {
                deregister(loadedKey);
            }
        }
    }

    private boolean hasBorrowWaiters() {
        for (K k : poolMap.keySet()) {
            final ObjectDeque<T> deque = poolMap.get(k);
            if (deque != null) {
                final LinkedBlockingDeque<PooledObject<T>> pool =
                    deque.getIdleObjects();
                if(pool.hasTakeWaiters()) {
                    return true;
                }
            }
        }
        return false;
    }


    /**
     * {@inheritDoc}
     * <p>
     * Successive activations of this method examine objects in keyed sub-pools
     * in sequence, cycling through the keys and examining objects in
     * oldest-to-youngest order within the keyed sub-pools.
     */
    @Override
    public void evict() throws Exception {
        assertOpen();

        if (getNumIdle() == 0) {
            return;
        }

        PooledObject<T> underTest = null;
        EvictionPolicy<T> evictionPolicy = getEvictionPolicy();

        synchronized (evictionLock) {
            EvictionConfig evictionConfig = new EvictionConfig(
                    getMinEvictableIdleTimeMillis(),
                    getSoftMinEvictableIdleTimeMillis(),
                    getMinIdlePerKey());

            boolean testWhileIdle = getTestWhileIdle();

            LinkedBlockingDeque<PooledObject<T>> idleObjects = null;

            for (int i = 0, m = getNumTests(); i < m; i++) {
                if(evictionIterator == null || !evictionIterator.hasNext()) {
                    if (evictionKeyIterator == null ||
                            !evictionKeyIterator.hasNext()) {
                        List<K> keyCopy = new ArrayList<K>();
                        Lock readLock = keyLock.readLock();
                        readLock.lock();
                        try {
                            keyCopy.addAll(poolKeyList);
                        } finally {
                            readLock.unlock();
                        }
                        evictionKeyIterator = keyCopy.iterator();
                    }
                    while (evictionKeyIterator.hasNext()) {
                        evictionKey = evictionKeyIterator.next();
                        ObjectDeque<T> objectDeque = poolMap.get(evictionKey);
                        if (objectDeque == null) {
                            continue;
                        }
                        idleObjects = objectDeque.getIdleObjects();

                        if (getLifo()) {
                            evictionIterator = idleObjects.descendingIterator();
                        } else {
                            evictionIterator = idleObjects.iterator();
                        }
                        if (evictionIterator.hasNext()) {
                            break;
                        }
                        evictionIterator = null;
                    }
                }
                if (evictionIterator == null) {
                    // Pools exhausted
                    return;
                }
                try {
                    underTest = evictionIterator.next();
                } catch (NoSuchElementException nsee) {
                    // Object was borrowed in another thread
                    // Don't count this as an eviction test so reduce i;
                    i--;
                    evictionIterator = null;
                    continue;
                }

                if (!underTest.startEvictionTest()) {
                    // Object was borrowed in another thread
                    // Don't count this as an eviction test so reduce i;
                    i--;
                    continue;
                }

                if (evictionPolicy.evict(evictionConfig, underTest,
                        poolMap.get(evictionKey).getIdleObjects().size())) {
                    destroy(evictionKey, underTest, true);
                    destroyedByEvictorCount.incrementAndGet();
                } else {
                    if (testWhileIdle) {
                        boolean active = false;
                        try {
                            factory.activateObject(evictionKey,
                                    underTest.getObject());
                            active = true;
                        } catch (Exception e) {
                            destroy(evictionKey, underTest, true);
                            destroyedByEvictorCount.incrementAndGet();
                        }
                        if (active) {
                            if (!factory.validateObject(evictionKey,
                                    underTest.getObject())) {
                                destroy(evictionKey, underTest, true);
                                destroyedByEvictorCount.incrementAndGet();
                            } else {
                                try {
                                    factory.passivateObject(evictionKey,
                                            underTest.getObject());
                                } catch (Exception e) {
                                    destroy(evictionKey, underTest, true);
                                    destroyedByEvictorCount.incrementAndGet();
                                }
                            }
                        }
                    }
                    if (!underTest.endEvictionTest(idleObjects)) {
                        // TODO - May need to add code here once additional
                        // states are used
                    }
                }
            }
        }
    }

    private PooledObject<T> create(K key) throws Exception {
        int maxTotalPerKey = getMaxTotalPerKey(); // Per key
        int maxTotal = getMaxTotal();   // All keys

        // Check against the overall limit
        boolean loop = true;

        while (loop) {
            int newNumTotal = numTotal.incrementAndGet();
            if (maxTotal > -1 && newNumTotal > maxTotal) {
                numTotal.decrementAndGet();
                if (getNumIdle() == 0) {
                    return null;
                } else {
                    clearOldest();
                }
            } else {
                loop = false;
            }
        }

        ObjectDeque<T> objectDeque = poolMap.get(key);
        long newCreateCount = objectDeque.getCreateCount().incrementAndGet();

        // Check against the per key limit
        if (maxTotalPerKey > -1 && newCreateCount > maxTotalPerKey ||
                newCreateCount > Integer.MAX_VALUE) {
            numTotal.decrementAndGet();
            objectDeque.getCreateCount().decrementAndGet();
            return null;
        }


        T t = null;
        try {
            t = factory.makeObject(key);
        } catch (Exception e) {
            numTotal.decrementAndGet();
            throw e;
        }

        PooledObject<T> p = new PooledObject<T>(t);
        createdCount.incrementAndGet();
        objectDeque.getAllObjects().put(t, p);
        return p;
    }

    private boolean destroy(K key, PooledObject<T> toDestroy, boolean always)
            throws Exception {

        ObjectDeque<T> objectDeque = register(key);

        try {
            boolean isIdle = objectDeque.getIdleObjects().remove(toDestroy);

            if (isIdle || always) {
                objectDeque.getAllObjects().remove(toDestroy.getObject());
                toDestroy.invalidate();

                try {
                    factory.destroyObject(key, toDestroy.getObject());
                } finally {
                    objectDeque.getCreateCount().decrementAndGet();
                    destroyedCount.incrementAndGet();
                    numTotal.decrementAndGet();
                }
                return true;
            } else {
                return false;
            }
        } finally {
            deregister(key);
        }
    }


    /*
     * register() and deregister() must always be used as a pair.
     */
    private ObjectDeque<T> register(K k) {
        Lock lock = keyLock.readLock();
        ObjectDeque<T> objectDeque = null;
        try {
            lock.lock();
            objectDeque = poolMap.get(k);
            if (objectDeque == null) {
                // Upgrade to write lock
                lock.unlock();
                lock = keyLock.writeLock();
                lock.lock();
                objectDeque = poolMap.get(k);
                if (objectDeque == null) {
                    objectDeque = new ObjectDeque<T>();
                    objectDeque.getNumInterested().incrementAndGet();
                    // NOTE: Keys must always be added to both poolMap and
                    //       poolKeyList at the same time while protected by
                    //       keyLock.writeLock()
                    poolMap.put(k, objectDeque);
                    poolKeyList.add(k);
                } else {
                    objectDeque.getNumInterested().incrementAndGet();
                }
            } else {
                objectDeque.getNumInterested().incrementAndGet();
            }
        } finally {
            lock.unlock();
        }
        return objectDeque;
    }

    /*
     * register() and deregister() must always be used as a pair.
     */
    private void deregister(K k) {
        ObjectDeque<T> objectDeque;

        objectDeque = poolMap.get(k);
        long numInterested = objectDeque.getNumInterested().decrementAndGet();
        if (numInterested == 0 && objectDeque.getCreateCount().get() == 0) {
            // Potential to remove key
            Lock writeLock = keyLock.writeLock();
            writeLock.lock();
            try {
                if (objectDeque.getCreateCount().get() == 0 &&
                        objectDeque.getNumInterested().get() == 0) {
                    // NOTE: Keys must always be removed from both poolMap and
                    //       poolKeyList at the same time while protected by
                    //       keyLock.writeLock()
                    poolMap.remove(k);
                    poolKeyList.remove(k);
                }
            } finally {
                writeLock.unlock();
            }
        }
    }

    @Override
    void ensureMinIdle() throws Exception {
        int minIdlePerKey = getMinIdlePerKey();
        if (minIdlePerKey < 1) {
            return;
        }

        for (K k : poolMap.keySet()) {
            ensureMinIdle(k);
        }
    }

    private void ensureMinIdle(K key) throws Exception {
        // Calculate current pool objects
        ObjectDeque<T> objectDeque = poolMap.get(key);
        // Protect against NPEs in case the key has been removed
        if (objectDeque == null) {
            return;
        }

        // this method isn't synchronized so the
        // calculateDeficit is done at the beginning
        // as a loop limit and a second time inside the loop
        // to stop when another thread already returned the
        // needed objects
        int deficit = calculateDeficit(objectDeque);

        for (int i = 0; i < deficit && calculateDeficit(objectDeque) > 0; i++) {
            addObject(key);
        }
    }

    /**
     * Create an object using the {@link KeyedPoolableObjectFactory#makeObject
     * factory}, passivate it, and then place it in the idle object pool.
     * <code>addObject</code> is useful for "pre-loading" a pool with idle
     * objects.
     *
     * @param key the key a new instance should be added to
     *
     * @throws Exception when {@link KeyedPoolableObjectFactory#makeObject}
     *                   fails.
     */
    @Override
    public void addObject(K key) throws Exception {
        assertOpen();
        register(key);
        try {
            PooledObject<T> p = create(key);
            addIdleObject(key, p);
        } finally {
            deregister(key);
        }
    }

    private void addIdleObject(K key, PooledObject<T> p) throws Exception {

        if (p != null) {
            factory.passivateObject(key, p.getObject());
            LinkedBlockingDeque<PooledObject<T>> idleObjects =
                    poolMap.get(key).getIdleObjects();
            if (getLifo()) {
                idleObjects.addFirst(p);
            } else {
                idleObjects.addLast(p);
            }
        }
    }

    /**
     * Registers a key for pool control and ensures that
     * {@link #getMinIdlePerKey()} idle instances are created.
     *
     * @param key - The key to register for pool control.
     */
    public void preparePool(K key) throws Exception {
        int minIdlePerKey = getMinIdlePerKey();
        if (minIdlePerKey < 1) {
            return;
        }
        ensureMinIdle(key);
    }

    private int getNumTests() {
        int totalIdle = getNumIdle();
        int numTests = getNumTestsPerEvictionRun();
        if (numTests >= 0) {
            return Math.min(numTests, totalIdle);
        }
        return(int)(Math.ceil(totalIdle/Math.abs((double)numTests)));
    }

    private int calculateDeficit(ObjectDeque<T> objectDeque) {

        if (objectDeque == null) {
            return getMinIdlePerKey();
        }

        // Used more than once so keep a local copy so the value is consistent
        int maxTotal = getMaxTotal();
        int maxTotalPerKey = getMaxTotalPerKey();

        int objectDefecit = 0;

        // Calculate no of objects needed to be created, in order to have
        // the number of pooled objects < maxTotalPerKey();
        objectDefecit = getMinIdlePerKey() - objectDeque.getIdleObjects().size();
        if (maxTotalPerKey > 0) {
            int growLimit = Math.max(0,
                    maxTotalPerKey - objectDeque.getIdleObjects().size());
            objectDefecit = Math.min(objectDefecit, growLimit);
        }

        // Take the maxTotal limit into account
        if (maxTotal > 0) {
            int growLimit = Math.max(0, maxTotal - getNumActive() - getNumIdle());
            objectDefecit = Math.min(objectDefecit, growLimit);
        }

        return objectDefecit;
    }


    //--- JMX support ----------------------------------------------------------

    @Override
    public Map<String,Integer> getNumActivePerKey() {
        HashMap<String,Integer> result = new HashMap<String,Integer>();

        Iterator<Entry<K,ObjectDeque<T>>> iter = poolMap.entrySet().iterator();
        while (iter.hasNext()) {
            Entry<K,ObjectDeque<T>> entry = iter.next();
            if (entry != null) {
                K key = entry.getKey();
                ObjectDeque<T> objectDequeue = entry.getValue();
                if (key != null && objectDequeue != null) {
                    result.put(key.toString(), Integer.valueOf(
                            objectDequeue.getAllObjects().size() -
                            objectDequeue.getIdleObjects().size()));
                }
            }
        }
        return result;
    }

    /**
     * Return an estimate of the number of threads currently blocked waiting for
     * an object from the pool. This is intended for monitoring only, not for
     * synchronization control.
     */
    @Override
    public int getNumWaiters() {
        int result = 0;

        if (getBlockWhenExhausted()) {
            Iterator<ObjectDeque<T>> iter = poolMap.values().iterator();

            while (iter.hasNext()) {
                // Assume no overflow
                result += iter.next().getIdleObjects().getTakeQueueLength();
            }
        }

        return result;
    }

    /**
     * Return an estimate of the number of threads currently blocked waiting for
     * an object from the pool for the given key. This is intended for
     * monitoring only, not for synchronization control.
     */
    @Override
    public int getNumWaiters(K key) {
        if (getBlockWhenExhausted()) {
            final ObjectDeque<T> objectDeque = poolMap.get(key);
            if (objectDeque == null) {
                return 0;
            } else {
                return objectDeque.getIdleObjects().getTakeQueueLength();
            }
        } else {
            return 0;
        }
    }

    @Override
    public List<K> getKeys() {
        List<K> keyCopy = new ArrayList<K>();
        Lock readLock = keyLock.readLock();
        readLock.lock();
        try {
            keyCopy.addAll(poolKeyList);
        } finally {
            readLock.unlock();
        }
        return keyCopy;
    }


    //--- inner classes ----------------------------------------------

    /*
     * Maintains information on the per key queue for a given key.
     */
    private class ObjectDeque<S> {

        private final LinkedBlockingDeque<PooledObject<S>> idleObjects =
                new LinkedBlockingDeque<PooledObject<S>>();

        /*
         * Number of instances created - number destroyed.
         * Invariant: createCount <= maxTotalPerKey
         */
        private final AtomicInteger createCount = new AtomicInteger(0);

        /*
         * The map is keyed on pooled instances.  Note: pooled instances
         * <em>must</em> be distinguishable by equals for this structure to
         * work properly.
         */
        private final Map<S, PooledObject<S>> allObjects =
                new ConcurrentHashMap<S, PooledObject<S>>();

        /*
         * Number of threads with registered interest in this key.
         * register(K) increments this counter and deRegister(K) decrements it.
         * Invariant: empty keyed pool will not be dropped unless numInterested
         *            is 0.
         */
        private final AtomicLong numInterested = new AtomicLong(0);

        public LinkedBlockingDeque<PooledObject<S>> getIdleObjects() {
            return idleObjects;
        }

        public AtomicInteger getCreateCount() {
            return createCount;
        }

        public AtomicLong getNumInterested() {
            return numInterested;
        }

        public Map<S, PooledObject<S>> getAllObjects() {
            return allObjects;
        }
    }

    //--- configuration attributes ---------------------------------------------
    private volatile int maxIdlePerKey =
            GenericKeyedObjectPoolConfig.DEFAULT_MAX_IDLE_PER_KEY;
    private volatile int minIdlePerKey =
        GenericKeyedObjectPoolConfig.DEFAULT_MIN_IDLE_PER_KEY;
    private volatile int maxTotalPerKey =
        GenericKeyedObjectPoolConfig.DEFAULT_MAX_TOTAL_PER_KEY;
    private final KeyedPoolableObjectFactory<K,T> factory;


    //--- internal attributes --------------------------------------------------

    /*
     * My hash of sub-pools (ObjectQueue). The list of keys <b>must</b> be kept
     * in step with {@link #poolKeyList} using {@link #keyLock} to ensure any
     * changes to the list of current keys is made in a thread-safe manner.
     */
    private final Map<K,ObjectDeque<T>> poolMap =
            new ConcurrentHashMap<K,ObjectDeque<T>>(); // @GuardedBy("keyLock") for write access (and some read access)
    /*
     * List of pool keys - used to control eviction order. The list of keys
     * <b>must</b> be kept in step with {@link #poolMap} using {@link #keyLock}
     * to ensure any changes to the list of current keys is made in a
     * thread-safe manner.
     */
    private final List<K> poolKeyList = new ArrayList<K>(); // @GuardedBy("keyLock")
    private final ReadWriteLock keyLock = new ReentrantReadWriteLock(true);
    /*
     * The combined count of the currently active objects for all keys and those
     * in the process of being created. Under load, it may exceed
     * {@link #maxTotal} but there will never be more than {@link #maxTotal}
     * created at any one time.
     */
    private final AtomicInteger numTotal = new AtomicInteger(0);
    private Iterator<K> evictionKeyIterator = null; // @GuardedBy("evictionLock")
    private K evictionKey = null; // @GuardedBy("evictionLock")

    // JMX specific attributes
    private static final String ONAME_BASE =
        "org.apache.commoms.pool2:type=GenericKeyedObjectPool,name=";
}
