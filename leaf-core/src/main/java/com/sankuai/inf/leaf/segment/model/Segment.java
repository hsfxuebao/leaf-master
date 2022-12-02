package com.sankuai.inf.leaf.segment.model;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 号段类
 */
public class Segment {
    /**
     * 内存生成的每一个id号
     */
    private AtomicLong value = new AtomicLong(0);
    /**
     * 当前号段允许的最大id值
     */
    private volatile long max;
    /**
     * 步长，会根据数据库的step动态调整
     */
    private volatile int step;
    /**
     * 当前号段所属的SegmentBuffer
     */
    private SegmentBuffer buffer;

    public Segment(SegmentBuffer buffer) {
        this.buffer = buffer;
    }

    public AtomicLong getValue() {
        return value;
    }

    public void setValue(AtomicLong value) {
        this.value = value;
    }

    public long getMax() {
        return max;
    }

    public void setMax(long max) {
        this.max = max;
    }

    public int getStep() {
        return step;
    }

    public void setStep(int step) {
        this.step = step;
    }

    public SegmentBuffer getBuffer() {
        return buffer;
    }

    /**
     * 获取号段的剩余量
     * @return
     */
    public long getIdle() {
        return this.getMax() - getValue().get();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Segment(");
        sb.append("value:");
        sb.append(value);
        sb.append(",max:");
        sb.append(max);
        sb.append(",step:");
        sb.append(step);
        sb.append(")");
        return sb.toString();
    }
}
