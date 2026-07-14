package edu.whu.tmdb.query.operations.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import edu.whu.tmdb.query.Transaction;
import edu.whu.tmdb.query.operations.Select;
import edu.whu.tmdb.query.operations.Update;
import edu.whu.tmdb.query.operations.Exception.ErrorList;
import edu.whu.tmdb.query.operations.Exception.TMDBException;
import edu.whu.tmdb.query.operations.utils.MemConnect;
import edu.whu.tmdb.query.operations.utils.SelectResult;
import edu.whu.tmdb.storage.memory.MemManager;
import edu.whu.tmdb.storage.memory.Tuple;
import edu.whu.tmdb.storage.memory.TupleList;
import edu.whu.tmdb.storage.memory.SystemTable.BiPointerTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.DeputyRuleTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.DeputyTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.SwitchingTableItem;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.update.UpdateSet;

public class UpdateImpl implements Update {

    private final MemConnect memConnect;
    

    // 根据源类对象id获取代理对象id
    public int getDeputyObjId(int oriOid) {
        for(BiPointerTableItem bpi : MemConnect.getBiPointerTableList()) {
            if(bpi.objectid == oriOid) {
                return bpi.deputyobjectid;
            }
        }
        return -1;
    }

    // 根据源类对象id获取代理对象id
    public List<Integer> getDeputyObjIdList(int oriOid) {
        List<Integer> res = new ArrayList<>();
        for(BiPointerTableItem bpi : MemConnect.getBiPointerTableList()) {
            if(bpi.objectid == oriOid) {
                res.add(bpi.deputyobjectid);
            }
        }
        return res;
    }

    // 给定源类id，代理对象的新旧id，设置代理对象的新id
    public void setDeputyObjId(int oriId, int oldId, int newId) {
        for(BiPointerTableItem bpi : MemConnect.getBiPointerTableList()) {
            if(bpi.objectid == oriId && bpi.deputyobjectid == oldId) {
                bpi.deputyobjectid = newId;
            }
        }
    }

    // 给定代理对象的新旧id，设置代理对象的新id，适用于换整个分组
    public void setDeputyObjId(int oldId, int newId) {
        for(BiPointerTableItem bpi : MemConnect.getBiPointerTableList()) {
            if(bpi.deputyobjectid == oldId) {
                bpi.deputyobjectid = newId;
            }
        }
    }

    public TupleList except(TupleList ltuplelist,TupleList rtuplelist){
        TupleList tupleList=new TupleList();
        List<Tuple> set=new ArrayList<>();
        for(Tuple tuple:ltuplelist.tuplelist){
            set.add(tuple);
        }
        //如果2中含有tuple，则在结果集合中移除
        int len = set.size();
        for(Tuple tuple:rtuplelist.tuplelist){

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
    public UpdateImpl() { this.memConnect = MemConnect.getInstance(MemManager.getInstance()); }

    @Override
    public void update(Statement stmt) throws JSQLParserException, TMDBException, IOException {
        execute((net.sf.jsqlparser.statement.update.Update) stmt);
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

    public void execute(net.sf.jsqlparser.statement.update.Update updateStmt) throws JSQLParserException, TMDBException, IOException {
        // 1.update语句(类名/属性名)存在性检测
        String updateTableName = updateStmt.getTable().getName();
        if (!memConnect.classExist(updateTableName)) {
            throw new TMDBException(ErrorList.CLASS_NAME_DOES_NOT_EXIST, updateTableName);
        }
        ArrayList<UpdateSet> updateSetStmts = updateStmt.getUpdateSets();    // update语句中set字段列表
        ArrayList<String> setAttrNames = new ArrayList<>(); // set字段的属性名
        for (UpdateSet updateSetStmt : updateSetStmts) {
            String columnName = updateSetStmt.getColumns().get(0).getColumnName();
            if (!memConnect.columnExist(updateTableName, columnName)) {
                throw new TMDBException(ErrorList.COLUMN_NAME_DOES_NOT_EXIST, columnName);
            }
            setAttrNames.add(columnName);
        }

        // 2.获取符合where条件的所有元组
        String sql = "select * from " + updateTableName + " where " + updateStmt.getWhere().toString() + ";";
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(sql.getBytes());
        net.sf.jsqlparser.statement.select.Select parse = (net.sf.jsqlparser.statement.select.Select) CCJSqlParserUtil.parse(byteArrayInputStream);
        Select select = new SelectImpl();
        SelectResult selectResult = select.select(parse);   // 注：selectResult均为临时副本，不是源数据

        // 3.执行update操作
        int[] indexs = new int[updateSetStmts.size()];      // update中set语句修改的属性->类表中属性的映射关系
        Object[] updateValue = new Object[updateSetStmts.size()];
        setMapping(selectResult.getAttrname(), updateSetStmts, indexs, updateValue);
        int classId = memConnect.getClassId(updateTableName);
        String whereAttrName = getColumnName(updateStmt.getWhere());
        update(selectResult.getTpl(), indexs, updateValue, classId, setAttrNames, whereAttrName);
    }

    /**
     * update的具体执行过程
     * @param tupleList   经筛选得到的tuple list副本（只包含tuple属性）
     * @param indexs      update中set语句修改的属性->类表中属性的映射关系
     * @param updateValue set语句中的第i个对应于源类中第j个属性修改后的值
     * @param classId     修改表的id
     */
    /* group代理的update分为下面几种：
     * 对非分组属性更新，不改bipointer
     * 对分组属性更新，可能改bipointer，可能不改
     */
    public void update(TupleList tupleList, int[] indexs, Object[] updateValue, int classId, ArrayList<String> setAttrNames, String whereAttrName) throws TMDBException, IOException {
        // 1.更新源类tuple
        ArrayList<Integer> updateIdList = new ArrayList<>();
        Set<Integer> groupDeputyTupleIdSet = new HashSet<>(); // 存储group代理类元组的ID，确保唯一性
        HashMap<Integer, Tuple> oldDptOid2newObjs = new HashMap<>(); //存储代理oid到更新后的tuple的映射关系
        for (Tuple tuple : tupleList.tuplelist) {
            for (int i = 0; i < indexs.length; i++) {
                tuple.tuple[indexs[i]] = updateValue[i];
            }
            memConnect.UpateTuple(tuple, tuple.getTupleId());
            updateIdList.add(tuple.getTupleId());

            // 任务一：同步更新非严格代理
            ImpreciseDeputyManager.handleImpreciseUpdate(classId, tuple.getTupleId());
            GroupDeputyManager.onSourceUpdate(classId, tuple.getTupleId());

            List<Integer> dptObjIds = getDeputyObjIdList(tuple.getTupleId());
            for(int i : dptObjIds) {
                oldDptOid2newObjs.put(i, tuple);
            }
            // GroupDeputy 已由 GroupDeputyManager 按完整规则重建，避免再次进入旧增量分支。
        }
        HashSet<Integer> deputyset = new HashSet<>();
        // 2.根据biPointerTable找到对应的deputyTuple
        ArrayList<Integer> deputyTupleIdList = new ArrayList<>();
        TupleList deputyTupleList = new TupleList();    // 所有代理类的元组
        for (BiPointerTableItem biPointerTableItem : MemConnect.getBiPointerTableList()) {
            if (updateIdList.contains(biPointerTableItem.objectid)) {
                deputyTupleIdList.add(biPointerTableItem.deputyobjectid);
                Tuple tuple = memConnect.GetTuple(biPointerTableItem.deputyobjectid);
                deputyset.add(biPointerTableItem.deputyid);
                deputyTupleList.addTuple(tuple);
            }
        }
        if (deputyTupleIdList.isEmpty()) { return; }

        // 3.获取deputyTupleId->...的哈希映射列表 
        //   判断修改列是否为join连接条件 by zmjk
        boolean isLink = false;
        boolean aboutdeputy = true;
        List<Integer> collect = Arrays.stream(indexs).boxed().collect(Collectors.toList());
        HashMap<Integer, ArrayList<Integer>> deputyId2AttrId = new HashMap<>();         // 满足where条件的deputyId -> deputyAttrIdList(其实也是index)
        HashMap<Integer, ArrayList<Object>> deputyId2UpdateValue = new HashMap<>();     // 满足where条件的deputyId -> 更新后的属性值列表(其实也是updateValue)
        for (SwitchingTableItem switchingTableItem : MemConnect.getSwitchingTableList()) {
            if (switchingTableItem.oriId == classId && collect.contains(switchingTableItem.oriAttrid)) {
                if (!deputyId2AttrId.containsKey(switchingTableItem.deputyId)) {
                    deputyId2AttrId.put(switchingTableItem.deputyId, new ArrayList<>());
                    deputyId2UpdateValue.put(switchingTableItem.deputyId, new ArrayList<>());
                }
                if(switchingTableItem.rule=="joindeputy"){
                    ArrayList<String> joinColumns = extractColumnNames(getdeputyrule(switchingTableItem.deputyId,2));
                    String oriAttrName = memConnect.getColumn(switchingTableItem.oriId, switchingTableItem.oriAttrid);
                    if(joinColumns.contains(oriAttrName)){
                        isLink = true;
                    }  
                }
                deputyId2AttrId.get(switchingTableItem.deputyId).add(switchingTableItem.deputyAttrId);
                int tempIndex = collect.indexOf(switchingTableItem.oriAttrid);
                deputyId2UpdateValue.get(switchingTableItem.deputyId).add(updateValue[tempIndex]);
            }
        }

        // 4.递归修改所有代理类
        for (int deputyId : deputyset)//遍历每个代理类，在这里处理非group的情况
        {   
            switch (getdeputyrule(deputyId, 1)) {
                case "joindeputy":   
                {
                    if(isLink||aboutdeputy) //如果更新了连接条件或涉及代理规则相关属性的更改，要删除不符合代理规则的元组和BiPointerTableItem，增加新的符合代理规则的元组和BiPointerTableItem 
                    {
                        String stmt = getdeputyrule(deputyId,0);
                        Transaction transaction = Transaction.getInstance();    // 创建一个事务实例
                        SelectResult selectResult = null;
                        String deputyname = memConnect.getClassName(deputyId);
                        try {
                            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(stmt.getBytes());
                            Statement selectstmt = CCJSqlParserUtil.parse(byteArrayInputStream);
                            selectResult = transaction.joinquery("", -1, selectstmt,tupleList);
                        }catch (JSQLParserException e) {
                            // e.printStackTrace();    // 打印语法错误的堆栈信息
                            System.out.println("syntax error");
                        }//获取代理规则查询的结果
                        DeleteImpl deletetemp = new DeleteImpl();
                        TupleList inserttuplelist = selectResult.getTpl(); //获取要新增的对象，就是将更新后的元组与其他源类连接生成的新代理类元组
                        TupleList deletetuplelist = deputyTupleList;//获取要删除的对象,就是要代理类要更新的元组
                        if( inserttuplelist.tuplelist.size()!=0) //插入满足代理规则的新对象
                        {
                            try {
                                int objectNum = inserttuplelist.tuplenum;
                                List<String> attrNamesList = memConnect.getColumns(deputyname);
                                HashSet<String> processedClassNames = new HashSet<>();
                                String [] classNames = selectResult.getClassName();
                                for (String className : classNames) { //hash表对classid去重
                                    if (processedClassNames.contains(className)) {
                                        continue;
                                    }
                                    processedClassNames.add(className);   
                                }
                                InsertImpl insertImpl = new InsertImpl();
                                for (int i = 0; i < objectNum; i++) {
                                    Tuple t = inserttuplelist.tuplelist.get(i);
                                    int deputyObjectId = insertImpl.executeOne(deputyId, attrNamesList, new Tuple(
                                        t.tupleSize, 
                                        t.tupleId, 
                                        t.classId, 
                                        t.tupleIds.clone(), 
                                        t.tuple.clone(), 
                                        t.delete));
                        
                                    // 创建 BiPointerTableItem 对象并设置相关
                                    Map<String, Integer> classTupleMap = new HashMap<>();
                                    for (int j = 0; j < classNames.length; j++) {
                                    classTupleMap.put(classNames[j], t.tupleIds[j]);
                                    }
                                    for(String className : processedClassNames){
                                        int originId = memConnect.getClassId(className);
                                        BiPointerTableItem biPointerItem = new BiPointerTableItem(originId, classTupleMap.get(className), deputyId, deputyObjectId);
            
                                        // 使用 MemConnect.getBiPointerTableList().add() 插入 BiPointerTable
                                        MemConnect.getBiPointerTableList().add(biPointerItem);
                                    }
                                }

                            }catch (IOException e)
                            {
                                System.out.println("insert error");
                            }
                        }
                        if(deletetuplelist.tuplelist.size()!=0)
                        {
                            deletetemp.delete(deletetuplelist);//删除不满足代理规则的对象
                        }
                    }else{
                        TupleList updateTupleList = new TupleList();
                        for (Tuple tuple : deputyTupleList.tuplelist) {
                            if (tuple.classId == deputyId) {
                                updateTupleList.addTuple(tuple);    // 找到该代理类的所有元组
                            }
                        }
                        int[] nextIndexs = deputyId2AttrId.get(deputyId).stream().mapToInt(Integer -> Integer).toArray();
                        Object[] nextUpdate = deputyId2UpdateValue.get(deputyId).toArray();
                        update(updateTupleList, nextIndexs, nextUpdate, deputyId,null,null);
                        }
                }break;
                case "groupdeputy":  
                {
                    break;
                }
                case "uniondeputy":
                {
                    TupleList updateTupleList = new TupleList();
                    for (Tuple tuple : deputyTupleList.tuplelist) {
                        if (tuple.classId == deputyId) {
                            updateTupleList.addTuple(tuple);    // 找到该代理类的所有元组
                        }
                    }
                    int[] nextIndexs = deputyId2AttrId.get(deputyId).stream().mapToInt(Integer -> Integer).toArray();
                    Object[] nextUpdate = deputyId2UpdateValue.get(deputyId).toArray();
                    update(updateTupleList, nextIndexs, nextUpdate, deputyId,null,null);
                }   
                break;
                default:
                {
                    TupleList updateTupleList = new TupleList();
                    for (Tuple tuple : deputyTupleList.tuplelist) {
                        if (tuple.classId == deputyId) {
                            updateTupleList.addTuple(tuple);    // 找到该代理类的所有元组
                        }
                    }
                    int[] nextIndexs = deputyId2AttrId.get(deputyId).stream().mapToInt(Integer -> Integer).toArray();
                    Object[] nextUpdate = deputyId2UpdateValue.get(deputyId).toArray();
                    update(updateTupleList, nextIndexs, nextUpdate, deputyId,null,null);
                }
                break;
            }
        }

        // 处理group代理类
        if (!groupDeputyTupleIdSet.isEmpty()) { 
            for (int deputyTupleId : groupDeputyTupleIdSet) {
                // 这里拿到的tuple是还没更新的代理类元组
                Tuple tuple = memConnect.GetTuple(deputyTupleId);
                // 获取代理属性名
                String groupAttr = "";
                for(SwitchingTableItem switchingTableItem : MemConnect.getSwitchingTableList()) {
                    if(switchingTableItem.oriId ==classId && switchingTableItem.deputyId == tuple.classId) {
                        groupAttr = switchingTableItem.oriAttr;
                        break;
                    }
                }

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
                SelectImpl selecttemp = new SelectImpl();
                Tuple t = selecttemp.aggOne(groupObj, changedList, funcNames, pIndexs);

                if (setAttrNames.contains(groupAttr)) { // 要set分组属性
                    // 取得分组属性（在源类中）的索引，并且找到源类中这个属性的新值
                    int groupAttrIdx = memConnect.getAttrid(classId, groupAttr);
                    Tuple newOriTuple = oldDptOid2newObjs.get(tuple.tupleId);
                    // if (newOriTuple == null) { return; } // 有可能没有源对象，直接返回
                    // for (Map.Entry<Integer, Tuple> entry : oldDptOid2newObjs.entrySet()) {
                    //     Integer key = entry.getKey();
                    //     Object[] valueArray = entry.getValue().tuple;
                    //     System.out.println("Key: " + key + ", Value: " + Arrays.toString(valueArray));
                    // }

                    if (Objects.equals(groupAttr, whereAttrName)) { // where条件也是分组属性，相当于整个分组要改名
                        // 先删除旧分组
                        DeleteImpl deletetemp = new DeleteImpl();
                        deletetemp.deleteTupleOnly(tuple);
                        int oldGroupSize = 0; // 统计旧分组有多少被改了
                        for (BiPointerTableItem bpi : memConnect.getBiPointerTableList()) {
                            if(bpi.deputyobjectid == tuple.tupleId) {
                                oldGroupSize+=1;
                            }
                        }
                        // 对新结果进行查询，funcname和pindex可以沿用上次查询
                        Object newObj = newOriTuple.tuple[groupAttrIdx];
                        try {
                            String sql = "select * from " + memConnect.getClassName(classId) + " where " + groupAttr + " = " + newObj.toString() + ";";
                            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(sql.getBytes());
                            Statement selectstmt = CCJSqlParserUtil.parse(byteArrayInputStream);
                            selectResult = transaction.query("", -1, selectstmt);
                        }catch (JSQLParserException e) {
                            System.out.println("syntax error");
                        }
                        TupleList newList = selectResult.getTpl();
                        Tuple newt = selecttemp.aggOne(newObj, newList, funcNames, pIndexs);

                        // 全部换到一个新分组
                        if(newList.tuplenum == oldGroupSize) {
                            String dptName = memConnect.getClassName(deputyClassId);
                            InsertImpl inserttemp = new InsertImpl();
                            int inserttupleid= inserttemp.executeOne(deputyClassId, memConnect.getColumns(dptName), newt);
                            setDeputyObjId(tuple.tupleId, inserttupleid);
                        }
                        // 全部换到一个已有分组
                        else {
                            // 找到换进分组的分组依据代理对象id
                            TupleList oldDeputyTpl = memConnect.getTupleList(deputyClassId);
                            int groupObjDptId = -1;
                            Tuple oldt = null;
                            for(Tuple tempt : oldDeputyTpl.tuplelist) {
                                if(Objects.equals(tempt.tuple[0], newObj)) {
                                    groupObjDptId = tempt.tupleId;
                                    oldt = tempt;
                                }
                            }
                            updateOne(oldt, newt);
                            setDeputyObjId(tuple.tupleId, groupObjDptId);
                        }
                    }
                    else { // 对分组的一部分进行了调整
                        // 检查是否进行了换组，如果当前分组属性值与更新后对应的值一样就没有换组
                        if (Objects.equals(groupObj, newOriTuple.tuple[groupAttrIdx])) {
                            updateOne(tuple, t);
                        }
                        else { // 换组了，也需要算出旧组的值，并更新bipointer指向新组
                            // 先更新旧组，如果旧组空了，就删除
                            if(changedList.tuplenum == 0) {
                                DeleteImpl deletetemp = new DeleteImpl();
                                deletetemp.deleteTupleOnly(tuple);
                            }
                            else {
                                updateOne(tuple, t);
                            }

                            // 对新结果进行查询，funcname和pindex可以沿用上次查询
                            Object newObj = newOriTuple.tuple[groupAttrIdx];
                            try {
                                String sql = "select * from " + memConnect.getClassName(classId) + " where " + groupAttr + " = " + newObj.toString() + ";";
                                ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(sql.getBytes());
                                Statement selectstmt = CCJSqlParserUtil.parse(byteArrayInputStream);
                                selectResult = transaction.query("", -1, selectstmt);
                            }catch (JSQLParserException e) {
                                System.out.println("syntax error");
                            }
                            TupleList newList = selectResult.getTpl();
                            Tuple newt = selecttemp.aggOne(newObj, newList, funcNames, pIndexs);

                            // 换到新的分组
                            if(newList.tuplenum == 1) {
                                // 直接插入一条新记录，然后更新双指针表
                                String dptName = memConnect.getClassName(deputyClassId);
                                InsertImpl inserttemp = new InsertImpl();
                                int inserttupleid= inserttemp.executeOne(deputyClassId, memConnect.getColumns(dptName), newt);
                                setDeputyObjId(newOriTuple.tupleId, tuple.tupleId, inserttupleid);
                            }
                            else {
                                // 找到换进分组的分组依据代理对象id
                                TupleList oldDeputyTpl = memConnect.getTupleList(deputyClassId);
                                int groupObjDptId = -1;
                                Tuple oldt = null;
                                for(Tuple tempt : oldDeputyTpl.tuplelist) {
                                    if(Objects.equals(tempt.tuple[0], newObj)) {
                                        groupObjDptId = tempt.tupleId;
                                        oldt = tempt;
                                    }
                                }
                                updateOne(oldt, newt);
                                setDeputyObjId(newOriTuple.tupleId, tuple.tupleId, groupObjDptId);
                            }
                        }
                    }
                }
                else { // 组内更新，新的结果已经求出，直接update一条tuple即可
                    updateOne(tuple, t);
                }
            
            }
        }
    }

    // 把新tuple的内容给旧tuple，oid继续沿用旧的
    public void updateOne(Tuple oldTuple, Tuple newTuple) throws TMDBException, IOException {
        oldTuple.tuple = newTuple.tuple;
        memConnect.UpateTuple(oldTuple, oldTuple.getTupleId());
    }

    /**
     * 给定attrNames和updateSetStmts，对indexs和updateValue进行赋值
     * @param attrNames 满足更新条件元组的属性名列表
     * @param updateSetStmts update语句set字段列表
     * @param indexs 赋值：set字段属性->元组属性的位置对应关系
     * @param updateValue 赋值：set字段赋值列表
     */
    private void setMapping(String[] attrNames, ArrayList<UpdateSet> updateSetStmts, int[] indexs, Object[] updateValue) {
        for (int i = 0; i < updateSetStmts.size(); i++) {
            UpdateSet updateSet = updateSetStmts.get(i);
            for (int j = 0; j < attrNames.length; j++) {
                if (!updateSet.getColumns().get(0).getColumnName().equals(attrNames[j])) { continue; }

                // 如果set的属性在元组属性列表中，进行赋值
                if (updateSet.getExpressions().get(0) instanceof StringValue) {
                    updateValue[i] = ((StringValue) updateSet.getExpressions().get(0)).getValue();
                } else {
                    updateValue[i] = updateSet.getExpressions().get(0).toString();
                }
                indexs[i] = j;      // set语句中的第i个对应于源类中第j个属性
                break;
            }
        }
    }
/**
     * 获得代理规则
     * 0：stmt, 1：deputyType, 2:Join条件
     */
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

    public static ArrayList<String> extractColumnNames(String columnNames) {
        ArrayList<String> list = new ArrayList<>();
        if (columnNames != null && !columnNames.isEmpty()) {
            // 按逗号分隔，并使用 trim() 去除可能的空格
            String[] columns = columnNames.split(",");
            for (String col : columns) {
                list.add(col.trim());
            }
        }
        return list;
    }

}
