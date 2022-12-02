package com.sankuai.inf.leaf.server.service;

import com.sankuai.inf.leaf.IDGen;
import com.sankuai.inf.leaf.common.PropertyFactory;
import com.sankuai.inf.leaf.common.Result;
import com.sankuai.inf.leaf.common.ZeroIDGen;
import com.sankuai.inf.leaf.server.Constants;
import com.sankuai.inf.leaf.server.exception.InitException;
import com.sankuai.inf.leaf.snowflake.SnowflakeIDGenImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Properties;

/**
 * snowflake模式的service层
 */
@Service("SnowflakeService")
public class SnowflakeService {
    private Logger logger = LoggerFactory.getLogger(SnowflakeService.class);

    /**
     * ID生成器
     */
    private IDGen idGen;

    /**
     * 构造函数，注入单例SnowflakeService时，完成以下几件事：
     * 1. 加载leaf.properties配置文件解析配置
     * 2. 创建snowflake模式ID生成器
     * 3. 初始化ID生成器
     * @throws InitException
     */
    public SnowflakeService() throws InitException {
        // 1. 加载leaf.properties配置文件解析配置
        Properties properties = PropertyFactory.getProperties();
        // 是否开启snowflake模式
        boolean flag = Boolean.parseBoolean(properties.getProperty(Constants.LEAF_SNOWFLAKE_ENABLE, "true"));
        if (flag) {
            // 2. 创建snowflake模式ID生成器
            String zkAddress = properties.getProperty(Constants.LEAF_SNOWFLAKE_ZK_ADDRESS);
            int port = Integer.parseInt(properties.getProperty(Constants.LEAF_SNOWFLAKE_PORT));
            idGen = new SnowflakeIDGenImpl(zkAddress, port);
            // 3. 初始化ID生成器
            if(idGen.init()) {
                logger.info("Snowflake Service Init Successfully");
            } else {
                throw new InitException("Snowflake Service Init Fail");
            }
        } else {
            // ZeroIDGen一直返回id=0
            idGen = new ZeroIDGen();
            logger.info("Zero ID Gen Service Init Successfully");
        }
    }

    /**
     * 通过ID生成器获得key对应的id
     * @param key
     * @return
     */
    public Result getId(String key) {
        return idGen.get(key);
    }
}
