package com.sankuai.inf.leaf.snowflake;

import com.google.common.base.Preconditions;
import com.sankuai.inf.leaf.IDGen;
import com.sankuai.inf.leaf.common.Result;
import com.sankuai.inf.leaf.common.Status;
import com.sankuai.inf.leaf.common.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

/**
 * snowflake模式ID生成器
 */
public class SnowflakeIDGenImpl implements IDGen {

    /**
     * 初始化直接返回为true
     * @return
     */
    @Override
    public boolean init() {
        return true;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(SnowflakeIDGenImpl.class);

    /**
     * 起始时间戳，用于用当前时间戳减去这个时间戳，算出偏移量
     */
    private final long twepoch;
    /**
     * workID占用的比特数
     */
    private final long workerIdBits = 10L;
    /**
     * 最大能够分配的workerid =1023
     */
    private final long maxWorkerId = ~(-1L << workerIdBits);//最大能够分配的workerid =1023
    /**
     * 自增序列号
     */
    private final long sequenceBits = 12L;
    /**
     * workID左移位数为自增序列号的位数
     */
    private final long workerIdShift = sequenceBits;
    /**
     * 时间戳的左移位数为 自增序列号的位数+workID的位数
     */
    private final long timestampLeftShift = sequenceBits + workerIdBits;
    /**
     * 后12位都为1
     */
    private final long sequenceMask = ~(-1L << sequenceBits);
    /**
     * 保存该节点的workId
     */
    private long workerId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;
    private static final Random RANDOM = new Random();

    public SnowflakeIDGenImpl(String zkAddress, int port) {
        //Thu Nov 04 2010 09:42:54 GMT+0800 (中国标准时间) 
        this(zkAddress, port, 1288834974657L);
    }

    /**
     * @param zkAddress zk地址
     * @param port      snowflake监听端口
     * @param twepoch   起始的时间戳
     */
    public SnowflakeIDGenImpl(String zkAddress, int port, long twepoch) {
        this.twepoch = twepoch;
        Preconditions.checkArgument(timeGen() > twepoch, "Snowflake not support twepoch gt currentTime");
        final String ip = Utils.getIp();
        // 创建SnowflakeZookeeperHolder对象
        SnowflakeZookeeperHolder holder = new SnowflakeZookeeperHolder(ip, String.valueOf(port), zkAddress);
        LOGGER.info("twepoch:{} ,ip:{} ,zkAddress:{} port:{}", twepoch, ip, zkAddress, port);
        // todo 初始化SnowflakeZookeeperHolder对象
        boolean initFlag = holder.init();
        if (initFlag) {
            workerId = holder.getWorkerID();
            LOGGER.info("START SUCCESS USE ZK WORKERID-{}", workerId);
        } else {
            // 校验initFlag是否为true，不为true报出Snowflake Id Gen is not init ok错误
            Preconditions.checkArgument(initFlag, "Snowflake Id Gen is not init ok");
        }
        // 校验生成的workID必须在0~1023之间
        Preconditions.checkArgument(workerId >= 0 && workerId <= maxWorkerId, "workerID must gte 0 and lte 1023");
    }

    /**
     * 根据key获取id
     * 这是一个synchronized同步方法，确保原子性，所以sequence就是普通类型的变量值
     * @param key 业务key
     * @return
     */
    @Override
    public synchronized Result get(String key) {
        /**
         * 生成id号需要的时间戳和序列号
         * 1. 时间戳要求大于等于上一次用的时间戳 (这里主要解决机器工作时NTP时间回退问题)
         * 2. 序列号在时间戳相等的情况下要递增，大于的情况下回到起点
         */

        // 获取当前时间戳，timestamp用于记录生成id的时间戳
        long timestamp = timeGen();
        // 如果比上一次记录的时间戳早，也就是NTP造成时间回退了
        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            // 如果相差小于5
            if (offset <= 5) {
                try {
                    // 等待 2*offset ms就可以唤醒重新尝试获取锁继续执行
                    wait(offset << 1);
                    // todo 重新获取当前时间戳，理论上这次应该比上一次记录的时间戳迟了
                    timestamp = timeGen();
                    // 如果还是早，这绝对有问题的
                    if (timestamp < lastTimestamp) {
                        return new Result(-1, Status.EXCEPTION);
                    }
                } catch (InterruptedException e) {
                    LOGGER.error("wait interrupted");
                    return new Result(-2, Status.EXCEPTION);
                }
            // 如果差的比较大，直接返回异常
            } else {
                return new Result(-3, Status.EXCEPTION);
            }
        }
        // 如果从上一个逻辑分支产生的timestamp仍然和lastTimestamp相等
        if (lastTimestamp == timestamp) {
            // 自增序列+1然后取后12位的值
            sequence = (sequence + 1) & sequenceMask;
            // seq 为0的时候表示当前毫秒12位自增序列用完了，应该用下一毫秒时间来区别，否则就重复了
            if (sequence == 0) {
                // 对seq做随机作为起始
                sequence = RANDOM.nextInt(100);
                // todo 生成比lastTimestamp滞后的时间戳，这里不进行wait，因为很快就能获得滞后的毫秒数
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            //如果是新的ms开始,序列号要重新回到大致的起点
            sequence = RANDOM.nextInt(100);
        }
        // 记录这次请求id的时间戳，用于下一个请求进行比较
        lastTimestamp = timestamp;
        /**
         * 利用生成的时间戳、序列号和workID组合成id
         */
        long id = ((timestamp - twepoch) << timestampLeftShift) | (workerId << workerIdShift) | sequence;
        return new Result(id, Status.SUCCESS);

    }

    /**
     * 自旋生成直到比lastTimestamp之后的当前时间戳
     * @param lastTimestamp
     * @return
     */
    protected long tilNextMillis(long lastTimestamp) {
        long timestamp = timeGen();
        while (timestamp <= lastTimestamp) {
            timestamp = timeGen();
        }
        return timestamp;
    }

    /**
     * 生成时间戳
     * @return
     */
    protected long timeGen() {
        return System.currentTimeMillis();
    }

    public long getWorkerId() {
        return workerId;
    }

}
