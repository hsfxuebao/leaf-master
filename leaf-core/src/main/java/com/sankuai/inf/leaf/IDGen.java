package com.sankuai.inf.leaf;

import com.sankuai.inf.leaf.common.Result;

public interface IDGen {
    /**
     * 获取指定key下一个id
     * @param key
     * @return
     */
    Result get(String key);
    /**
     * 初始化
     * @return
     */
    boolean init();
}
