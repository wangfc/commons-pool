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

package org.apache.commons.pool2;

import org.apache.commons.pool2.KeyedObjectPool;
import org.apache.commons.pool2.KeyedObjectPoolFactory;
import org.apache.commons.pool2.KeyedPoolableObjectFactory;
import org.apache.commons.pool2.PoolUtils;

import junit.framework.TestCase;

/**
 * Tests for all {@link KeyedObjectPoolFactory}s.
 *
 * @author Sandy McArthur
 * @version $Revision$ $Date$
 */
public abstract class TestKeyedObjectPoolFactory extends TestCase {
    protected TestKeyedObjectPoolFactory(final String name) {
        super(name);
    }

    /**
     * @throws UnsupportedOperationException when this is unsupported by this KeyedPoolableObjectFactory type.
     */
    protected KeyedObjectPoolFactory<Object,Object> makeFactory() throws UnsupportedOperationException {
        return makeFactory(createObjectFactory());
    }

    /**
     * @throws UnsupportedOperationException when this is unsupported by this KeyedPoolableObjectFactory type.
     */
    protected abstract KeyedObjectPoolFactory<Object,Object> makeFactory(KeyedPoolableObjectFactory<Object,Object> objectFactory) throws UnsupportedOperationException;

    protected static KeyedPoolableObjectFactory<Object,Object> createObjectFactory() {
        return PoolUtils.adapt(new MethodCallPoolableObjectFactory());
    }

    public void testCreatePool() throws Exception {
        final KeyedObjectPoolFactory<Object,Object> factory;
        try {
            factory = makeFactory();
        } catch (UnsupportedOperationException uoe) {
            return;
        }
        final KeyedObjectPool<Object,Object> pool = factory.createPool();
        pool.close();
    }

    public void testToString() {
        final KeyedObjectPoolFactory<Object,Object> factory;
        try {
            factory = makeFactory();
        } catch (UnsupportedOperationException uoe) {
            return;
        }
        factory.toString();
    }
}
