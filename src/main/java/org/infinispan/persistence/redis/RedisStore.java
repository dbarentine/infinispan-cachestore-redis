package org.infinispan.persistence.redis;

import org.infinispan.persistence.redis.configuration.RedisStoreConfiguration;
import org.infinispan.commons.configuration.ConfiguredBy;
import org.infinispan.commons.io.ByteBuffer;
import org.infinispan.filter.KeyFilter;
import org.infinispan.marshall.core.MarshalledEntry;
import org.infinispan.persistence.TaskContextImpl;
import org.infinispan.persistence.spi.*;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;
import org.redisson.Config;
import org.redisson.Redisson;

import java.nio.charset.Charset;
import java.util.concurrent.Executor;
import net.jcip.annotations.ThreadSafe;
import org.redisson.core.RBucket;

@ThreadSafe
@ConfiguredBy(RedisStoreConfiguration.class)
final public class RedisStore implements AdvancedLoadWriteStore
{
    private static final Log log = LogFactory.getLog(RedisStore.class, Log.class);

    private InitializationContext ctx;
    private RedisStoreConfiguration configuration;

    private Redisson redisson;

    /**
     * Used to initialize a cache loader.  Typically invoked by the {@link org.infinispan.persistence.manager.PersistenceManager}
     * when setting up cache loaders.
     *
     * @throws PersistenceException in case of an error, e.g. communicating with the external storage
     */
    @Override
    public void init(InitializationContext ctx)
    {
        RedisStore.log.info("Redis cache store initialising");

        this.ctx = ctx;
        this.configuration = this.ctx.getConfiguration();

        Config config = new Config();

        // todo: choose correct configuration strategy
//        config.useClusterServers();
//            .setClientName()
//            .setDatabase()
//            .setLoadBalancer()
//            .setPassword()
//            .setRetryAttempts()
//            .setRetryInterval()
//            .setTimeout()
//
//            .setMasterConnectionPoolSize()
//            .setSlaveConnectionPoolSize()
//
//            .setScanInterval()
//
//            .addNodeAddress()
//        ;
//
//        config.useSentinelConnection()
//            .setClientName()
//            .setDatabase()
//            .setLoadBalancer()
//            .setPassword()
//            .setRetryAttempts()
//            .setRetryInterval()
//            .setTimeout()
//
//            .setMasterConnectionPoolSize()
//            .setSlaveConnectionPoolSize()
//
//            .setMasterName()
//            .addSentinelAddress()
//        ;
//
//        config.useMasterSlaveConnection()
//            .setClientName()
//            .setDatabase()
//            .setLoadBalancer()
//            .setPassword()
//            .setRetryAttempts()
//            .setRetryInterval()
//            .setTimeout()
//
//            .setMasterConnectionPoolSize()
//            .setSlaveConnectionPoolSize()
//
//            .setMasterAddress("")
//            .addSlaveAddress()

        redisson = Redisson.create(config);
    }

    /**
     * Invoked on component start
     */
    @Override
    public void start()
    {

    }

    /**
     * Invoked on component stop
     */
    @Override
    public void stop()
    {
        if (null != this.redisson) {
            this.redisson.shutdown();
        }
    }

    /**
     * Iterates in parallel over the entries in the storage using the threads from the <b>executor</b> pool. For each
     * entry the {@link CacheLoaderTask#processEntry(MarshalledEntry, TaskContext)} is
     * invoked. Before passing an entry to the callback task, the entry should be validated against the <b>filter</b>.
     * Implementors should build an {@link TaskContext} instance (implementation) that is fed to the {@link
     * CacheLoaderTask} on every invocation. The {@link CacheLoaderTask} might invoke {@link
     * org.infinispan.persistence.spi.AdvancedCacheLoader.TaskContext#stop()} at any time, so implementors of this method
     * should verify TaskContext's state for early termination of iteration. The method should only return once the
     * iteration is complete or as soon as possible in the case TaskContext.stop() is invoked.
     *
     * @param filter        to validate which entries should be feed into the task. Might be null.
     * @param task          callback to be invoked in parallel for each stored entry that passes the filter check
     * @param executor      an external thread pool to be used for parallel iteration
     * @param fetchValue    whether or not to fetch the value from the persistent store. E.g. if the iteration is
     *                      intended only over the key set, no point fetching the values from the persistent store as
     *                      well
     * @param fetchMetadata whether or not to fetch the metadata from the persistent store. E.g. if the iteration is
     *                      intended only ove the key set, then no pint fetching the metadata from the persistent store
     *                      as well
     * @throws PersistenceException in case of an error, e.g. communicating with the external storage
     */
    @Override
    public void process(final KeyFilter filter, final CacheLoaderTask task, Executor executor, boolean fetchValue, boolean fetchMetadata)
    {
        final InitializationContext ctx = this.ctx;
        final TaskContext taskContext = new TaskContextImpl();
        Redisson redisson = this.redisson;

        try {
            for (final String keyRaw : redisson.findKeysByPattern("*")) {
                if (taskContext.isStopped()) {
                    break;
                }

                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Object key = ctx.getMarshaller().objectFromByteBuffer(keyRaw);

                            if (filter.accept(key)) {
                                byte[] valueRaw = jedis.get(keyRaw);
                                ByteBuffer keyBuf = ctx.getByteBufferFactory().newByteBuffer(keyRaw, 0, keyRaw.length);
                                ByteBuffer valueBuf = ctx.getByteBufferFactory().newByteBuffer(valueRaw, 0, valueRaw.length);

                                task.processEntry(
                                    ctx.getMarshalledEntryFactory().newMarshalledEntry(keyBuf, valueBuf, (ByteBuffer) null),
                                    taskContext
                                );
                            }
                        }
                        catch(Exception ex) {
                            RedisStore.log.error("Failed to process the redis store key", ex);
                            throw new PersistenceException(ex);
                        }
                    }
                });
            }
        }
        catch(Exception ex) {
            RedisStore.log.error("Failed to process the redis store keys", ex);
            throw new PersistenceException(ex);
        }
    }

    /**
     * Using the thread in the pool, remove all the expired data from the persistence storage. For each removed entry,
     * the supplied listener is invoked.
     *
     * @throws PersistenceException in case of an error, e.g. communicating with the external storage
     */
    @Override
    public void purge(Executor executor, final PurgeListener purgeListener)
    {
        // Nothing to purge, redis is set to expire data itself
    }

    /**
     * Returns the number of elements in the store.
     *
     * @throws PersistenceException in case of an error, e.g. communicating with the external storage
     */
    @Override
    public int size()
    {
        try {
            long dbSize = jedis.dbSize();

            // Can't return more than Integer.MAX_VALUE due to interface limitation
            // If the number of elements in redis is more than the int max size,
            // log the anomaly and return the int max size
            if (dbSize > Integer.MAX_VALUE) {
                RedisStore.log.info(
                    String.format("Redis store is holding more elements than we can count! " +
                            "Total number of elements found %d. Limited to returning count as %d",
                        dbSize, Integer.MAX_VALUE
                    )
                );

                return Integer.MAX_VALUE;
            }
            else {
                return (int) dbSize;
            }
        }
        catch(Exception ex) {
            RedisStore.log.error("Failed to fetch element count from the redis store", ex);
            throw new PersistenceException(ex);
        }
    }

    /**
     * Removes all the data from the storage.
     *
     * @throws PersistenceException in case of an error, e.g. communicating with the external storage
     */
    @Override
    public void clear()
    {
        try {
            this.redisson.flushdb();
        }
        catch(Exception ex) {
            RedisStore.log.error("Failed to clear all elements in the redis store", ex);
            throw new PersistenceException(ex);
        }
    }

    /**
     * Fetches an entry from the storage. If a {@link MarshalledEntry} needs to be created here, {@link
     * org.infinispan.persistence.spi.InitializationContext#getMarshalledEntryFactory()} and {@link
     * InitializationContext#getByteBufferFactory()} should be used.
     *
     * @return the entry, or null if the entry does not exist
     * @throws PersistenceException in case of an error, e.g. communicating with the external storage
     */
    @Override
    public MarshalledEntry load(Object key)
    {
        try {
            ByteBuffer keyRaw = this.ctx.getMarshaller().objectToBuffer(key);
            RBucket bucket = redisson.getBucket(new String(keyRaw.getBuf(), Charset.forName("UTF-8")));
            byte[] value = bucket.get();

            if (null == value) {
                return null;
            }

            ByteBuffer valueBuf = this.ctx.getByteBufferFactory().newByteBuffer(value, 0, value.length);
            return this.ctx.getMarshalledEntryFactory().newMarshalledEntry(keyRaw, valueBuf, (ByteBuffer) null);
        }
        catch(Exception ex) {
            RedisStore.log.error("Failed to load element from the redis store", ex);
            throw new PersistenceException(ex);
        }
    }

    /**
     * Persists the entry to the storage.
     *
     * @throws PersistenceException in case of an error, e.g. communicating with the external storage
     * @see MarshalledEntry
     */
    @Override
    public void write(MarshalledEntry marshalledEntry)
    {
        ByteBuffer buf = marshalledEntry.getValueBytes();

        try {
            byte[] key = marshalledEntry.getKeyBytes().getBuf();
            RBucket bucket = redisson.getBucket(new String(key, Charset.forName("UTF-8")));
            bucket.set(buf.getBuf());
//            jedis.expire(key, 100); // todo: set based on configuration
        }
        catch(Exception ex) {
            RedisStore.log.error("Failed to write element to the redis store", ex);
            throw new PersistenceException(ex);
        }
    }

    /**
     * Delete the entry for the given key from the store
     *
     * @return true if the entry existed in the persistent store and it was deleted.
     * @throws PersistenceException in case of an error, e.g. communicating with the external storage
     */
    @Override
    public boolean delete(Object key)
    {
        try {
            ByteBuffer marshaledBuff = this.ctx.getMarshaller().objectToBuffer(key);
            return redisson.delete(new String(marshaledBuff.getBuf(), Charset.forName("UTF-8"))) > 0;
        }
        catch(Exception ex) {
            RedisStore.log.error("Failed to delete element from the redis store", ex);
            throw new PersistenceException(ex);
        }
    }

    /**
     * Returns true if the storage contains an entry associated with the given key.
     *
     * @return True if the cache contains the key specified
     * @throws PersistenceException in case of an error, e.g. communicating with the external storage
     */
    @Override
    public boolean contains(Object key)
    {
        try {
            ByteBuffer marshaledBuff = this.ctx.getMarshaller().objectToBuffer(key);
            return null != redisson.getBucket(new String(marshaledBuff.getBuf(), Charset.forName("UTF-8")));
        }
        catch(Exception ex) {
            RedisStore.log.error("Failed to discover if element is in the redis store", ex);
            throw new PersistenceException(ex);
        }
    }
}
