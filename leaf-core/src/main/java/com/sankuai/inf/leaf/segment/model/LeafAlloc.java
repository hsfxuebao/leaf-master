package com.sankuai.inf.leaf.segment.model;

/**
 * 分配bean，和数据库表记录基本对应
 */
public class LeafAlloc {
    // 对应biz_tag
    private String key;
    // 对应最大id
    private long maxId;
    // 对应步长
    private int step;
    // 对应更新时间
    private String updateTime;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public long getMaxId() {
        return maxId;
    }

    public void setMaxId(long maxId) {
        this.maxId = maxId;
    }

    public int getStep() {
        return step;
    }

    public void setStep(int step) {
        this.step = step;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }
}
