package edu.whu.tmdb.query.operations.impl;


import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import edu.whu.tmdb.query.Transaction;
import edu.whu.tmdb.query.operations.Delete;
import edu.whu.tmdb.query.operations.Select;
import edu.whu.tmdb.query.operations.Exception.TMDBException;
import edu.whu.tmdb.query.operations.utils.MemConnect;
import edu.whu.tmdb.query.operations.utils.SelectResult;
import edu.whu.tmdb.storage.memory.MemManager;
import edu.whu.tmdb.storage.memory.Tuple;
import edu.whu.tmdb.storage.memory.TupleList;
import edu.whu.tmdb.storage.memory.SystemTable.BiPointerTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.DeputyRuleTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.DeputyTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.ObjectTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.SwitchingTableItem;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;

public class DeleteImpl implements Delete {

    public DeleteImpl() {}

    @Override
    public void delete(Statement statement) throws JSQLParserException, TMDBException, IOException {
        execute((net.sf.jsqlparser.statement.delete.Delete) statement);
    }

    // 获取where条件属性名
    private static String getColumnName(Expression where) {
        if (where instanceof BinaryExpression) {  // BinaryExpression 是所有比较运算符的父类
            BinaryExpression binaryExpr = (BinaryExpression) where;
            if (binaryExpr.getLeftExpression() instanceof Column) {
                return ((Column) binaryExpr.getLeftExpression()).getColumnName();  // 获取列名
            }
        }
        return null;  // 不是二元表达式，返回 null
    }
    
    public void execute(net.sf.jsqlparser.statement.delete.Delete deleteStmt) throws JSQLParserException, TMDBException, IOException {
        // 1.获取符合where条件的所有元组
        Table table = deleteStmt.getTable();        // 获取需要删除的表名
        Expression where = deleteStmt.getWhere();   // 获取delete中的where表达式
        String sql = "select * from " + table;;
        if (where != null) {
            sql += " where " + String.valueOf(where) + ";";
        }
        String columnName = getColumnName(where);
        // System.out.println("属性名: " + columnName);
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(sql.getBytes());
        net.sf.jsqlparser.statement.select.Select parse = (net.sf.jsqlparser.statement.select.Select) CCJSqlParserUtil.parse(byteArrayInputStream);
        Select select = new SelectImpl();
        SelectResult selectResult = select.select(parse);

        // 2.执行delete
        delete(selectResult.getTpl(), columnName);
    }

    public TupleList except(TupleList ltpl, TupleList rtpl) {
        TupleList tupleList=new TupleList();
        List<Tuple> set=new ArrayList<>();
        for(Tuple tuple:ltpl.tuplelist){
            set.add(tuple);
        }
        //如果2中含有tuple，则在结果集合中移除
        int len = set.size();
        for(Tuple tuple:rtpl.tuplelist){
            for(int i = 0;i<len;i++)
            {
                if(Arrays.toString(tuple.tuple).equals(Arrays.toString(set.get(i).tuple)))
                {
                    len --; set.remove(i);break;
                }
            }

        }
        tupleList.tuplelist=set;
        tupleList.tuplenum=set.size();

        return tupleList;
    }
    
    /* delete group代理类分为两种：
     * 1.删分组属性，可以直接用原本的方法，根据bipointer递归删
     * 2.删不是分组属性，分两种：
     * 一是删完之后这个分组没了，这个和删整个分组一样；
     * 二是删完之后分组还在，需要算修改内容
     */
    public void delete(TupleList tupleList, String colName) throws TMDBException, IOException {
        MemConnect memConnect = MemConnect.getInstance(MemManager.getInstance());
        int tupleListSize = tupleList.tuplenum;
        if (tupleListSize == 0) return; //提前返回
        Set<Integer> deputyTupleIdSet = new HashSet<>(); // 存储代理类元组的ID，确保唯一性
        Set<Integer> groupDeputyTupleIdSet = new HashSet<>(); // 存储group代理类元组的ID，确保唯一性

        int classId = tupleList.tuplelist.get(0).classId;
        ArrayList<Integer> DeputyIdList = memConnect.getDeputyIdList(classId);
    
        // 1.删除源类tuple和object table
        for (int i = 0; i < tupleListSize; i++) {
            Tuple tuple = tupleList.tuplelist.get(i);
            int tupleId = tuple.getTupleId();
            // System.out.println("Delete tuple " + tupleId + "\n");
            // 任务一：同步删除非严格代理
            ImpreciseDeputyManager.handleImpreciseDelete(classId, tupleId);
            // 删除源类元组
            memConnect.DeleteTuple(tupleId);
            // 删除对象表中的条目
    
            Iterator<ObjectTableItem> iterator = MemConnect.getObjectTableList().iterator();
            while (iterator.hasNext()) {
                ObjectTableItem item = iterator.next();
                if (item.tupleid == tupleId) {
                    iterator.remove();
                }
            }
            GroupDeputyManager.onSourceDelete(classId, tupleId);
    
            // 查找代理类元组并添加到代理类ID列表
            List<BiPointerTableItem> biPointerTableList = MemConnect.getBiPointerTableList();
            for (BiPointerTableItem biPointerTableItem : biPointerTableList) {
                if (biPointerTableItem.objectid == tupleId) {
                    // 将group代理的单独列出
                    if (getdeputyrule(biPointerTableItem.deputyid, 1) == "groupdeputy") {
                        groupDeputyTupleIdSet.add(biPointerTableItem.deputyobjectid);
                    }
                    else {
                        deputyTupleIdSet.add(biPointerTableItem.deputyobjectid);
                    }
                }
            }
        }
    
        // 2.删除源类biPointerTable
        for (int k = 0; k < tupleListSize; k++) {
            Tuple tuple = tupleList.tuplelist.get(k);
            int tupleId = tuple.getTupleId();
            // 删除双指针表中的条目
            Iterator<BiPointerTableItem> iterator = MemConnect.getBiPointerTableList().iterator();
            while (iterator.hasNext()) {
                BiPointerTableItem biPointerTableItem = iterator.next();
                if(getdeputyrule(biPointerTableItem.deputyid,1)=="joindeputy" ){
                    if (biPointerTableItem.objectid == tupleId||biPointerTableItem.deputyobjectid == tupleId) { 
                        iterator.remove();
                    }
                }else {
                    if (biPointerTableItem.objectid == tupleId) { 
                        iterator.remove();
                    }
                }
            }
        }
    
        // 3. 根据biPointerTable递归删除代理类相关表
        if (!deputyTupleIdSet.isEmpty()) { 
            TupleList deputyTupleList = new TupleList();
            for (int deputyTupleId : deputyTupleIdSet) {
                Tuple tuple = memConnect.GetTuple(deputyTupleId);
                // System.out.println("Delete deputy tuple " + deputyTupleId + "\n");
                if (tuple != null) {
                    deputyTupleList.addTuple(tuple);
                }
            }
            delete(deputyTupleList, colName);
        }

        // 如果是需要特殊处理的group代理
        if (!groupDeputyTupleIdSet.isEmpty()) { 
            TupleList deputyTupleList = new TupleList(); // 如果删了整个分组，可以直接递归删除
            for (int deputyTupleId : groupDeputyTupleIdSet) {
                Tuple tuple = memConnect.GetTuple(deputyTupleId);
                if (tuple != null) {
                    // 获取分组属性的名称
                    String groupAttr = "";
                    for(SwitchingTableItem switchingTableItem : MemConnect.getSwitchingTableList()) {
                        if(switchingTableItem.oriId ==classId && switchingTableItem.deputyId == tuple.classId) {
                            groupAttr = switchingTableItem.oriAttr;
                            break;
                        }
                    }
                    // 如果删的就是分组属性，那么可以递归删除全部
                    if(Objects.equals(colName, groupAttr)) {
                        deputyTupleList.addTuple(tuple);
                    }
                    else {
                        Transaction transaction = Transaction.getInstance();    // 创建一个事务实例
                        SelectResult selectResult = null;
                        int deputyClassId = tuple.classId;

                        // 初始化分组依据以及聚合列信息
                        Object groupObj = tuple.tuple[0];
                        ArrayList<String> funcNames = new ArrayList<>();
                        ArrayList<Integer> pIndexs = new ArrayList<>();
                        for (SwitchingTableItem sti : MemConnect.getSwitchingTableList()) {
                            if(classId == sti.oriId && deputyClassId == sti.deputyId) {
                                if(!Objects.equals(sti.oriAttr, sti.deputyAttr)) { // 聚合列的源列名是不带聚合函数的，代理列名是带聚合函数的
                                    funcNames.add(sti.rule);
                                    pIndexs.add(memConnect.getAttrid(classId, sti.oriAttr));
                                }
                            }
                        }

                        // 执行对分组依据的查询（如果有索引，那么效率为log复杂度）
                        try {
                            String sql = "select * from " + memConnect.getClassName(classId) + " where " + groupAttr + " = " + groupObj.toString() + ";";
                            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(sql.getBytes());
                            Statement selectstmt = CCJSqlParserUtil.parse(byteArrayInputStream);
                            selectResult = transaction.query("", -1, selectstmt);
                        }catch (JSQLParserException e) {
                            System.out.println("syntax error");
                        }
                        TupleList changedList = selectResult.getTpl();

                        // 如果查询结果为空，那么直接递归删除
                        if(changedList.tuplenum == 0) {
                            deputyTupleList.addTuple(tuple);
                        }
                        else {
                            // 算新的聚合结果
                            SelectImpl selecttemp = new SelectImpl();
                            Tuple t = selecttemp.aggOne(groupObj, changedList, funcNames, pIndexs);
                            
                            // 把新聚合结果给旧tuple
                            UpdateImpl updatetemp = new UpdateImpl();
                            updatetemp.updateOne(tuple, t);

                            // 因为实际上没有删除，所以不用递归
                        }
                    }
                }
            }
            delete(deputyTupleList, colName);
        }
    }
    
    /*
     * delete for join,union
     */
    public void delete(TupleList tupleList) {
        MemConnect memConnect = MemConnect.getInstance(MemManager.getInstance());
        int tupleListSize = tupleList.tuplenum;
        Set<Integer> deputyTupleIdSet = new HashSet<>(); // 存储代理类元组的ID，确保唯一性
    
        // 1.删除源类tuple和object table
        for (int i = 0; i < tupleListSize; i++) {
            Tuple tuple = tupleList.tuplelist.get(i);
            int tupleId = tuple.getTupleId();
            // System.out.println(tupleId + "\n");
    
            // 删除源类元组
            memConnect.DeleteTuple(tupleId);
            // 删除对象表中的条目
    
            Iterator<ObjectTableItem> iterator = MemConnect.getObjectTableList().iterator();
            while (iterator.hasNext()) {
                ObjectTableItem item = iterator.next();
                if (item.tupleid == tupleId) {
                    iterator.remove();
                }
            }
    
            // 查找代理类元组并添加到代理类ID列表
            List<BiPointerTableItem> biPointerTableList = MemConnect.getBiPointerTableList();
            for (BiPointerTableItem biPointerTableItem : biPointerTableList) {
                if (biPointerTableItem.objectid == tupleId) {
                    deputyTupleIdSet.add(biPointerTableItem.deputyobjectid);
                }
            }
        }
    
        // 2.删除源类biPointerTable
        for (int k = 0; k < tupleListSize; k++) {
            Tuple tuple = tupleList.tuplelist.get(k);
            int tupleId = tuple.getTupleId();
            // 删除双指针表中的条目
            Iterator<BiPointerTableItem> iterator = MemConnect.getBiPointerTableList().iterator();
            while (iterator.hasNext()) {
                BiPointerTableItem biPointerTableItem = iterator.next();
                if (biPointerTableItem.objectid == tupleId||biPointerTableItem.deputyobjectid == tupleId) {//bby jinx
                    iterator.remove();
                }
            }
        }
    
        // 3. 根据biPointerTable递归删除代理类相关表
        if (deputyTupleIdSet.isEmpty()) { return; }
        TupleList deputyTupleList = new TupleList();
        for (Integer deputyTupleId : deputyTupleIdSet) {
            Tuple tuple = memConnect.GetTuple(deputyTupleId);
            if (tuple != null) {
                deputyTupleList.addTuple(tuple);
            }
        }
        delete(deputyTupleList);
    }

    public static String getdeputyrule(int deputyclassid, int index) {
        for(DeputyTableItem tempitem : MemConnect.getDeputyTableList())
        {
            if(tempitem.deputyid == deputyclassid)
                for(DeputyRuleTableItem tempruleitem : MemConnect.getDeputyRuleTableList())
                {
                    if(tempruleitem.ruleid == tempitem.ruleid)
                    return tempruleitem.deputyrule[index];
                }
        }
        return "";
    }

    // 只删记录，不改代理表。
    public void deleteTupleOnly(Tuple t) {
        MemConnect memConnect = MemConnect.getInstance(MemManager.getInstance());
        
        int tupleId = t.getTupleId();

        // 删除元组
        memConnect.DeleteTuple(tupleId);

        // 删除对象表中的条目
        Iterator<ObjectTableItem> iterator = MemConnect.getObjectTableList().iterator();
        while (iterator.hasNext()) {
            ObjectTableItem item = iterator.next();
            if (item.tupleid == tupleId) {
                iterator.remove();
            }
        }
    }
}
