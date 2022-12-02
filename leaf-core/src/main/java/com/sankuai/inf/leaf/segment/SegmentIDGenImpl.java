package com.sankuai.inf.leaf.segment;

import com.sankuai.inf.leaf.IDGen;
import com.sankuai.inf.leaf.common.Result;
import com.sankuai.inf.leaf.common.Status;
import com.sankuai.inf.leaf.segment.dao.IDAllocDao;
import com.sankuai.inf.leaf.segment.model.*;
import org.perf4j.StopWatch;
import org.perf4j.slf4j.Slf4JStopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 号段模式ID生成器
 */
public class SegmentIDGenImpl implements IDGen {
    private static final Logger logger = LoggerFactory.getLogger(SegmentIDGenImpl.class);

    /**
     * IDCache未初始化成功时的异常码
     */
    private static final long EXCEPTION_ID_IDCACHE_INIT_FALSE = -1;
    /**
     * key不存在时的异常码
     */
    private static final long EXCEPTION_ID_KEY_NOT_EXISTS = -2;
    /**
     * SegmentBuffer中的两个Segment均未从DB中装载时的异常码
     */
    private static final long EXCEPTION_ID_TWO_SEGMENTS_ARE_NULL = -3;
    /**
     * 最大步长不超过100,0000
     */
    private static final int MAX_STEP = 1000000;
    /**
     * 一个Segment维持时间为15分钟
     */
    private static final long SEGMENT_DURATION = 15 * 60 * 1000L;
    /**
     * 线程池，用于执行异步任务，比如异步准备双buffer中的另一个buffer
     */
    private ExecutorService service = new ThreadPoolExecutor(5, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), new UpdateThreadFactory());
    /**
     * 标记自己是否初始化完毕
     */
    private volatile boolean initOK = false;
    /**
     * cache，存储所有业务key对应双buffer号段，所以是基于内存的发号方式
     */
    private Map<String, SegmentBuffer> cache = new ConcurrentHashMap<String, SegmentBuffer>();
    /**
     * 查询数据库的dao
     */
    private IDAllocDao dao;

    public static class UpdateThreadFactory implements ThreadFactory {

        private static int threadInitNumber = 0;

        private static synchronized int nextThreadNum() {
            return threadInitNumber++;
        }

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "Thread-Segment-Update-" + nextThreadNum());
        }
    }

    @Override
    public boolean init() {
        logger.info("Init ...");
        // todo 确保加载到kv后才初始化成功
        updateCacheFromDb();
        initOK = true;
        // todo 启动延迟任务，然后每分钟执行一次
        // 主要还是每分钟从数据库中更新cache
        updateCacheFromDbAtEveryMinute();
        return initOK;
    }

    /**
     * 每分钟同步db到cache
     */
    private void updateCacheFromDbAtEveryMinute() {
        ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName("check-idCache-thread");
                t.setDaemon(true);
                return t;
            }
        });
        service.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                updateCacheFromDb();
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * 将数据库表中的tags同步到cache中
     */
    private void updateCacheFromDb() {
        logger.info("update cache from db");
        StopWatch sw = new Slf4JStopWatch();
        try {
            // 获取数据库中所有的 biz_tag
            List<String> dbTags = dao.getAllTags();
            if (dbTags == null || dbTags.isEmpty()) {
                return;
            }
            // 本地缓存里面的
            List<String> cacheTags = new ArrayList<String>(cache.keySet());
            // db里面的
            Set<String> insertTagsSet = new HashSet<>(dbTags);
            // 本地缓存
            Set<String> removeTagsSet = new HashSet<>(cacheTags);
            /** 下面两步操作：保证cache和数据库tags同步
             * 1. cache新增上数据库表后添加的tags
             * 2. cache删除掉数据库表后删除的tags
             */

            // 1. db中新加的tags灌进cache，并实例化初始对应的SegmentBuffer
            for(int i = 0; i < cacheTags.size(); i++){
                String tmp = cacheTags.get(i);
                if(insertTagsSet.contains(tmp)){
                    insertTagsSet.remove(tmp);
                }
            }
            for (String tag : insertTagsSet) {
                SegmentBuffer buffer = new SegmentBuffer();
                buffer.setKey(tag);
                // 零值初始化当前正在使用的Segment号段
                Segment segment = buffer.getCurrent();
                segment.setValue(new AtomicLong(0));
                segment.setMax(0);
                segment.setStep(0);
                cache.put(tag, buffer);
                logger.info("Add tag {} from db to IdCache, SegmentBuffer {}", tag, buffer);
            }
            // 2. cache中已失效的tags从cache删除
            for(int i = 0; i < dbTags.size(); i++){
                String tmp = dbTags.get(i);
                if(removeTagsSet.contains(tmp)){
                    removeTagsSet.remove(tmp);
                }
            }
            for (String tag : removeTagsSet) {
                cache.remove(tag);
                logger.info("Remove tag {} from IdCache", tag);
            }
        } catch (Exception e) {
            logger.warn("update cache from db exception", e);
        } finally {
            sw.stop("updateCacheFromDb");
        }
    }

    /**
     * 获取对应key的下一个id值
     * @param key
     * @return
     */
    @Override
    public Result get(final String key) {
        // 必须在 SegmentIDGenImpl 初始化后执行init()方法
        // 也就是必须将数据库中的tags加载到内存cache中，并开启定时同步任务
        if (!initOK) {
            return new Result(EXCEPTION_ID_IDCACHE_INIT_FALSE, Status.EXCEPTION);
        }
        if (cache.containsKey(key)) {
            // 获取cache中对应的SegmentBuffer，SegmentBuffer中包含双buffer，两个号段
            SegmentBuffer buffer = cache.get(key);
            // DCL
            // 双重判断，避免多线程重复执行SegmentBuffer的初始化值操作
            // 在get id前检查是否完成DB数据初始化cache中key对应的的SegmentBuffer(之前只是零值初始化)，需要保证线程安全
            if (!buffer.isInitOk()) {
                synchronized (buffer) {
                    if (!buffer.isInitOk()) {
                        // DB数据初始化SegmentBuffer
                        try {
                            // todo 根据数据库表中key对应的记录 来初始化SegmentBuffer当前正在使用的Segment
                            updateSegmentFromDb(key, buffer.getCurrent());
                            logger.info("Init buffer. Update leafkey {} {} from db", key, buffer.getCurrent());
                            // 加载成功
                            buffer.setInitOk(true);
                        } catch (Exception e) {
                            logger.warn("Init buffer {} exception", buffer.getCurrent(), e);
                        }
                    }
                }
            }
            // todo 从segmentBuffer中获取id
            return getIdFromSegmentBuffer(cache.get(key));
        }
        // cache中不存在对应的key，则返回异常错误
        return new Result(EXCEPTION_ID_KEY_NOT_EXISTS, Status.EXCEPTION);
    }

    /**
     * 从数据库表中读取数据更新SegmentBuffer中的Segment
     * @param key
     * @param segment
     */
    public void updateSegmentFromDb(String key, Segment segment) {
        StopWatch sw = new Slf4JStopWatch();
        /**
         * 1. 先设置SegmentBuffer
         */

        // 获取Segment号段所属的SegmentBuffer
        SegmentBuffer buffer = segment.getBuffer();
        LeafAlloc leafAlloc;
        // 如果buffer没有DB数据初始化(也就是第一次进行DB数据初始化)
        if (!buffer.isInitOk()) {
            // 更新数据库中key对应记录的maxId(maxId表示当前分配到的最大id，maxId=maxId+step)，并查询更新后的记录返回
            leafAlloc = dao.updateMaxIdAndGetLeafAlloc(key);
            // 数据库初始设置的step赋值给当前buffer的初始step，后面后动态调整
            buffer.setStep(leafAlloc.getStep());
            // leafAlloc中的step为DB中设置的step，buffer这里是未进行DB数据初始化的，所以DB中step代表动态调整的最小下限
            buffer.setMinStep(leafAlloc.getStep());//leafAlloc中的step为DB中的step

        // 如果buffer的更新时间是0（初始是0，也就是第二次调用updateSegmentFromDb()）
        } else if (buffer.getUpdateTimestamp() == 0) {
            // 更新数据库中key对应记录的maxId(maxId表示当前分配到的最大id，maxId=maxId+step)，并查询更新后的记录返回
            leafAlloc = dao.updateMaxIdAndGetLeafAlloc(key);
            // 记录buffer的更新时间
            buffer.setUpdateTimestamp(System.currentTimeMillis());
            buffer.setStep(leafAlloc.getStep());
            buffer.setMinStep(leafAlloc.getStep());//leafAlloc中的step为DB中的step
        // 第三次以及之后的进来 动态设置nextStep
        } else {
            // 距离上次更新数据 隔了多长时间
            long duration = System.currentTimeMillis() - buffer.getUpdateTimestamp();
            int nextStep = buffer.getStep();
            /**
             *  动态调整step
             *  1) duration < 15 分钟 : step 变为原来的2倍， 最大为 MAX_STEP
             *  2) 15分钟 <= duration < 30分钟 : nothing
             *  3) duration >= 30 分钟 : 缩小step, 最小为DB中配置的step
             *
             *  这样做的原因是认为15min一个号段大致满足需求
             *  如果updateSegmentFromDb()速度频繁(15min多次)，也就是
             *  如果15min这个时间就把step号段用完，为了降低数据库访问频率，我们可以扩大step大小
             *  相反如果将近30min才把号段内的id用完，则可以缩小step
             */

            // 小于15分钟:step 变为原来的2倍. 最大为 MAX_STEP
            if (duration < SEGMENT_DURATION) {
                if (nextStep * 2 > MAX_STEP) {
                    //do nothing
                } else {
                    // 重新设置步长。是原来步长的2倍，但是不能超过1000000
                    nextStep = nextStep * 2;
                }
            // 15分钟 < duration < 30分钟 : nothing
            } else if (duration < SEGMENT_DURATION * 2) {
                //do nothing with nextStep
            // duration > 30 分钟 : 缩小step ,最小为DB中配置的步数
            } else {
                // 缩短步长
                nextStep = nextStep / 2 >= buffer.getMinStep() ? nextStep / 2 : nextStep;
            }
            logger.info("leafKey[{}], step[{}], duration[{}mins], nextStep[{}]", key, buffer.getStep(), String.format("%.2f",((double)duration / (1000 * 60))), nextStep);
            /**
             * 根据动态调整的nextStep更新数据库相应的maxId
             */

            // 为了高效更新记录，创建一个LeafAlloc，仅设置必要的字段的信息
            LeafAlloc temp = new LeafAlloc();
            temp.setKey(key);
            temp.setStep(nextStep);
            // 根据动态调整的step更新数据库的maxId
            leafAlloc = dao.updateMaxIdByCustomStepAndGetLeafAlloc(temp);
            // 记录更新时间
            buffer.setUpdateTimestamp(System.currentTimeMillis());
            // 记录当前buffer的动态调整的step值
            buffer.setStep(nextStep);
            // leafAlloc的step为DB中的step，所以DB中的step值代表着下限
            buffer.setMinStep(leafAlloc.getStep());//leafAlloc的step为DB中的step
        }
        /**
         * 2. 准备当前Segment号段
         */

        // 设置Segment号段id的起始值，value就是id（start=max_id-step）
        // must set value before set max
        long value = leafAlloc.getMaxId() - buffer.getStep();
        //设置到segment的atomicInteger中
        segment.getValue().set(value);
        // 设置最大值 为现在的max_id
        segment.setMax(leafAlloc.getMaxId());
        // 设置step
        segment.setStep(buffer.getStep());
        sw.stop("updateSegmentFromDb", key + " " + segment);
    }

    /**
     * 从SegmentBuffer生成id返回
     * @param buffer
     * @return
     */
    public Result getIdFromSegmentBuffer(final SegmentBuffer buffer) {
        // 自旋获取id
        while (true) {
            // 获取buffer的共享读锁，在平时不操作Segment的情况下益于并发
            buffer.rLock().lock();
            try {
                // 获取当前正在使用的Segment
                final Segment segment = buffer.getCurrent();
                // ===============异步准备双buffer的另一个Segment==============
                // 1. 另一个Segment没有准备好
                // 2. 当前Segment已经使用超过10%则开始异步准备另一个Segment
                // 3. buffer中的threadRunning字段. 代表是否已经提交线程池运行，是否有其他线程已经开始进行另外号段的初始化工作.使用CAS进行更新保证buffer在任意时刻,只会有一个线程进行异步更新另外一个号段.
                if (!buffer.isNextReady() && (segment.getIdle() < 0.9 * segment.getStep()) && buffer.getThreadRunning().compareAndSet(false, true)) {
                    // 线程池异步执行【准备Segment】任务
                    service.execute(new Runnable() {
                        @Override
                        public void run() {
                            // 获取另一个segment
                            Segment next = buffer.getSegments()[buffer.nextPos()];
                            boolean updateOk = false;
                            // 这里就是初始化另一个segment
                            try {
                                // 从数据库表中准备Segment
                                updateSegmentFromDb(buffer.getKey(), next);
                                updateOk = true;
                                logger.info("update segment {} from db {}", buffer.getKey(), next);
                            } catch (Exception e) {
                                logger.warn(buffer.getKey() + " updateSegmentFromDb exception", e);
                            } finally {
                                // 如果准备成功，则通过独占写锁设置另一个Segment准备标记OK，threadRunning为false表示准备完毕
                                if (updateOk) {
                                    // 读写锁是不允许线程先获得读锁继续获得写锁，这里可以是因为这一段代码其实是线程池线程去完成的，不是获取到读锁的线程
                                    buffer.wLock().lock();
                                    buffer.setNextReady(true);
                                    buffer.getThreadRunning().set(false);
                                    buffer.wLock().unlock();
                                } else {
                                    // 失败了，则还是没有准备好，threadRunning恢复false，以便于下次获取id时重新再异步准备Segment
                                    buffer.getThreadRunning().set(false);
                                }
                            }
                        }
                    });
                }
                // 使用atomicInteger的自增长
                long value = segment.getValue().getAndIncrement();
                // 如果是小于的max_id的话，就可以直接返回了
                if (value < segment.getMax()) {
                    return new Result(value, Status.SUCCESS);
                }
            } finally {
                buffer.rLock().unlock();
            }
            // 等待 后台线程 更新下一个segment完事
            waitAndSleep(buffer);
            // 执行到这里，说明当前号段已经用完，应该切换另一个Segment号段使用

            // 获取独占式写锁
            buffer.wLock().lock();
            try {
                // 获取当前使用的Segment号段
                final Segment segment = buffer.getCurrent();
                // 重复获取value, 多线程执行时，Segment可能已经被其他线程切换。再次判断, 防止重复切换Segment
                long value = segment.getValue().getAndIncrement();
                if (value < segment.getMax()) {
                    return new Result(value, Status.SUCCESS);
                }
                // 执行到这里, 说明其他的线程没有进行Segment切换，并且当前号段所有号码用完，需要进行切换Segment
                // 如果准备好另一个Segment，直接切换
                if (buffer.isNextReady()) {
                    buffer.switchPos();
                    buffer.setNextReady(false);
                // 如果另一个Segment没有准备好，则返回异常双buffer全部用完
                } else {
                    logger.error("Both two segments in {} are not ready!", buffer);
                    return new Result(EXCEPTION_ID_TWO_SEGMENTS_ARE_NULL, Status.EXCEPTION);
                }
            } finally {
                buffer.wLock().unlock();
            }
        }
    }

    private void waitAndSleep(SegmentBuffer buffer) {
        int roll = 0;
        while (buffer.getThreadRunning().get()) {
            roll += 1;
            if(roll > 10000) {
                try {
                    TimeUnit.MILLISECONDS.sleep(10);
                    break;
                } catch (InterruptedException e) {
                    logger.warn("Thread {} Interrupted",Thread.currentThread().getName());
                    break;
                }
            }
        }
    }

    public List<LeafAlloc> getAllLeafAllocs() {
        return dao.getAllLeafAllocs();
    }

    public Map<String, SegmentBuffer> getCache() {
        return cache;
    }

    public IDAllocDao getDao() {
        return dao;
    }

    public void setDao(IDAllocDao dao) {
        this.dao = dao;
    }
}
