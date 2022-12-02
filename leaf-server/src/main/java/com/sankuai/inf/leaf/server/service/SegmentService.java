package com.sankuai.inf.leaf.server.service;

import com.alibaba.druid.pool.DruidDataSource;
import com.sankuai.inf.leaf.IDGen;
import com.sankuai.inf.leaf.common.PropertyFactory;
import com.sankuai.inf.leaf.common.Result;
import com.sankuai.inf.leaf.common.ZeroIDGen;
import com.sankuai.inf.leaf.segment.SegmentIDGenImpl;
import com.sankuai.inf.leaf.segment.dao.IDAllocDao;
import com.sankuai.inf.leaf.segment.dao.impl.IDAllocDaoImpl;
import com.sankuai.inf.leaf.server.Constants;
import com.sankuai.inf.leaf.server.exception.InitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.Properties;

@Service("SegmentService")
public class SegmentService {
    private Logger logger = LoggerFactory.getLogger(SegmentService.class);

    private IDGen idGen;
    private DruidDataSource dataSource;

    /**
     * 构造函数，注入单例SegmentService时，完成以下几件事：
     * 1. 加载leaf.properties配置文件解析配置
     * 2. 创建Druid dataSource
     * 3. 创建IDAllocDao
     * 4. 创建ID生成器实例SegmentIDGenImpl并初始化
     * @throws SQLException
     * @throws InitException
     */
    public SegmentService() throws SQLException, InitException {
        // 1. 加载leaf.properties配置文件
        Properties properties = PropertyFactory.getProperties();
        // 是否开启号段模式
        boolean flag = Boolean.parseBoolean(properties.getProperty(Constants.LEAF_SEGMENT_ENABLE, "true"));
        if (flag) {
            // Config dataSource
            // 2. 创建Druid dataSource
            dataSource = new DruidDataSource();
            dataSource.setUrl(properties.getProperty(Constants.LEAF_JDBC_URL));
            dataSource.setUsername(properties.getProperty(Constants.LEAF_JDBC_USERNAME));
            dataSource.setPassword(properties.getProperty(Constants.LEAF_JDBC_PASSWORD));
            dataSource.init();

            // Config Dao
            // 3. 创建Dao
            IDAllocDao dao = new IDAllocDaoImpl(dataSource);

            // Config ID Gen
            // 4. 创建ID生成器实例SegmentIDGenImpl
            idGen = new SegmentIDGenImpl();
            ((SegmentIDGenImpl) idGen).setDao(dao);
            // 初始化SegmentIDGenImpl(加载db的tags至内存cache中，并开启定时同步更新任务)
            if (idGen.init()) {
                logger.info("Segment Service Init Successfully");
            } else {
                throw new InitException("Segment Service Init Fail");
            }
        } else {
            // ZeroIDGen一直返回id=0
            idGen = new ZeroIDGen();
            logger.info("Zero ID Gen Service Init Successfully");
        }
    }

    /**
     * 根据key获取id
     * @param key
     * @return
     */
    public Result getId(String key) {
        return idGen.get(key);
    }

    /**
     * 获取号段模式id生成器SegmentIDGenImpl
     * @return
     */
    public SegmentIDGenImpl getIdGen() {
        if (idGen instanceof SegmentIDGenImpl) {
            return (SegmentIDGenImpl) idGen;
        }
        return null;
    }
}
