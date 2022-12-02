package com.sankuai.inf.leaf.segment.dao.impl;

import com.sankuai.inf.leaf.segment.dao.IDAllocDao;
import com.sankuai.inf.leaf.segment.dao.IDAllocMapper;
import com.sankuai.inf.leaf.segment.model.LeafAlloc;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import javax.sql.DataSource;
import java.util.List;

public class IDAllocDaoImpl implements IDAllocDao {

    SqlSessionFactory sqlSessionFactory;

    public IDAllocDaoImpl(DataSource dataSource) {
        // 手动初始化sqlSessionFactory
        TransactionFactory transactionFactory = new JdbcTransactionFactory();
        Environment environment = new Environment("development", transactionFactory, dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(IDAllocMapper.class);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    /**
     * 获取所有的业务key对应的发号配置
     * @return
     */
    @Override
    public List<LeafAlloc> getAllLeafAllocs() {
        SqlSession sqlSession = sqlSessionFactory.openSession(false);
        try {
            return sqlSession.selectList("com.sankuai.inf.leaf.segment.dao.IDAllocMapper.getAllLeafAllocs");
        } finally {
            sqlSession.close();
        }
    }

    /**
     * 更新数据库的最大id值，并返回LeafAlloc
     * @param tag
     * @return
     */
    @Override
    public LeafAlloc updateMaxIdAndGetLeafAlloc(String tag) {
        SqlSession sqlSession = sqlSessionFactory.openSession();
        try {
            // 更新tag对应记录中的max_id，max_id = max_id + step，step为数据库中设置的step
            sqlSession.update("com.sankuai.inf.leaf.segment.dao.IDAllocMapper.updateMaxId", tag);
            // 获取更新完的记录，封装成LeafAlloc对象返回
            LeafAlloc result = sqlSession.selectOne("com.sankuai.inf.leaf.segment.dao.IDAllocMapper.getLeafAlloc", tag);
            // 提交事务
            sqlSession.commit();
            return result;
        } finally {
            sqlSession.close();
        }
    }

    /**
     * 依据动态调整的step值，更新DB的最大id值，并返回更新后的记录
     * @param leafAlloc
     * @return
     */
    @Override
    public LeafAlloc updateMaxIdByCustomStepAndGetLeafAlloc(LeafAlloc leafAlloc) {
        SqlSession sqlSession = sqlSessionFactory.openSession();
        try {
            sqlSession.update("com.sankuai.inf.leaf.segment.dao.IDAllocMapper.updateMaxIdByCustomStep", leafAlloc);
            LeafAlloc result = sqlSession.selectOne("com.sankuai.inf.leaf.segment.dao.IDAllocMapper.getLeafAlloc", leafAlloc.getKey());
            sqlSession.commit();
            return result;
        } finally {
            sqlSession.close();
        }
    }

    /**
     * 从数据库查询出所有的biz_tag
     * @return
     */
    @Override
    public List<String> getAllTags() {
        SqlSession sqlSession = sqlSessionFactory.openSession(false);
        try {
            return sqlSession.selectList("com.sankuai.inf.leaf.segment.dao.IDAllocMapper.getAllTags");
        } finally {
            sqlSession.close();
        }
    }
}
