package edu.whu.tmdb.query.operations.impl;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import edu.whu.tmdb.query.operations.CreateDeputyClass;
import edu.whu.tmdb.query.operations.Exception.ErrorList;
import edu.whu.tmdb.query.operations.Exception.TMDBException;
import edu.whu.tmdb.query.operations.utils.MemConnect;
import edu.whu.tmdb.query.operations.utils.SelectResult;
import edu.whu.tmdb.storage.memory.MemManager;
import edu.whu.tmdb.storage.memory.Tuple;
import edu.whu.tmdb.storage.memory.TupleList;
import edu.whu.tmdb.storage.memory.SystemTable.AttributeTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.BiPointerTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.ClassTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.DeputyRuleTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.DeputyTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.SwitchingTableItem;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;
import net.sf.jsqlparser.statement.select.SelectItem;


public class CreateDeputyClassImpl implements CreateDeputyClass {
    private final MemConnect memConnect;

    public CreateDeputyClassImpl() { this.memConnect = MemConnect.getInstance(MemManager.getInstance()); }

    @Override
    public boolean createDeputyClass(Statement stmt) throws TMDBException, IOException {
        return execute((net.sf.jsqlparser.statement.create.deputyclass.CreateDeputyClass) stmt);
    }

    public boolean execute(net.sf.jsqlparser.statement.create.deputyclass.CreateDeputyClass stmt) throws TMDBException, IOException {
        // 1.获取代理类名、代理类型、select元组
        String deputyClassName = stmt.getDeputyClass().toString();  // 代理类名
        if (memConnect.classExist(deputyClassName)) {
            throw new TMDBException(ErrorList.TABLE_ALREADY_EXISTS, deputyClassName);
        }
        int deputyType = getDeputyType(stmt);   // 代理类型
        Select selectStmt = stmt.getSelect();
        SelectResult selectResult = getSelectResult(selectStmt);

        // 2.执行代理类创建
        return createDeputyClassStreamLine(selectResult, deputyType, deputyClassName, selectStmt);
    }

    public boolean createDeputyClassStreamLine(SelectResult selectResult, int deputyType, String deputyClassName, Select selectStmt) throws TMDBException, IOException {
        // 判断是否为非严格 SelectDeputy：SelectDeputy 且 SELECT 没有 WHERE 子句
        boolean isImprecise = false;
        if (deputyType == 0 && selectStmt.getSelectBody() instanceof net.sf.jsqlparser.statement.select.PlainSelect) {
            net.sf.jsqlparser.statement.select.PlainSelect plainSelect = (net.sf.jsqlparser.statement.select.PlainSelect) selectStmt.getSelectBody();
            if (plainSelect.getWhere() == null) {
                isImprecise = true;
                deputyType = 4; // 非严格 SelectDeputy 用 4 标记
            }
        }
        int deputyId = createDeputyClass(deputyClassName, selectResult, deputyType);
        createDeputyTableItem(selectResult, deputyType, deputyId, selectStmt);
        if (!isImprecise) {
            createBiPointerTableItem(selectResult, deputyId, deputyType);
        }
        return true;
    }

    /**
     * 创建代理类的实现，包含代理类classTableItem的创建
     * @param deputyClassName 代理类名称
     * @param selectResult 代理类包含的元组列表
     * @param deputyRule 代理规则
     * @return 新建代理类ID
     */
    private int createDeputyClass(String deputyClassName, SelectResult selectResult, int deputyRule) throws TMDBException {
        // 如果SelectResult属性名重复，抛出异常
        List<String> attrNamesList = Arrays.asList(selectResult.getAttrname());

        HashSet<String> attrNamesSet = new HashSet<>();
        for (String attrName : attrNamesList) {
            if (!attrNamesSet.add(attrName)) {
                // 说明存在重复的属性名称
                throw new TMDBException(ErrorList.DUPLICATE_COLUMN_NAME, attrName); // 传递重复的属性名称作为参数
            }
        }
        
        MemConnect.getClassTable().maxid++;
        int deputyClassId = MemConnect.getClassTable().maxid; // 获取下一个可用的类ID
        int attrNum = selectResult.getClassName().length; // 获取属性个数
    
        // 判断类名的唯一性（要满足唯一性约束）
        for (ClassTableItem item : MemConnect.getClassTableList()) {
            if (item.classname.equals(deputyClassName)) {
                throw new TMDBException(ErrorList.TABLE_ALREADY_EXISTS, deputyClassName);
            }
        }

        // 创建 ClassTableItem 和 AttributeTableItem
        MemConnect.getClassTableList().add(new ClassTableItem(deputyClassName, deputyClassId, attrNum, deputyRule));
        for (int i = 0; i < attrNum; i++) {
            // 创建 ClassTableItem
            AttributeTableItem attributeTableItem = new AttributeTableItem(
                deputyClassId,
                i,
                selectResult.getAttrname()[i],
                selectResult.getType()[i],
                "" // 别名暂时为空
            );
            MemConnect.getAttributeTableList().add(attributeTableItem);
        }
    
        return deputyClassId;
    }

    /**
     * 新建deputyTableItem和switchingTableItem
     * @param classNames 源类类名列表
     * @param deputyType 代理规则
     * @param deputyId 代理类id
     */
    public void createDeputyTableItem(SelectResult selectResult, int deputyType, int deputyId, Select selectStmt) throws TMDBException {
        // 使用 HashSet 进行 classId 的去重
        HashSet<Integer> processedClassIds = new HashSet<>();
    
        TupleList tpl = selectResult.getTpl();
        int tupleNum = tpl.tuplenum;
        String []deputyrule = new String[3];
        deputyrule[0] = selectStmt.toString();
        deputyrule[2] = "";

        switch (deputyType) {
            case 0:   deputyrule[1] = "selectdeputy"; break;
            case 1:   deputyrule[1] = "joindeputy"; break;
            case 2:   deputyrule[1] = "uniondeputy"; break;
            case 3:   deputyrule[1] = "groupdeputy"; break;
            case 4:   deputyrule[1] = "impreciseselectdeputy"; break; // 新增：非严格 SelectDeputy
        }
        // 创建 DeputyRuleTableItem 对象并添加到列表中
        MemConnect.getDeputyRuleTable().maxruleid++;
        int deputyRuleId = MemConnect.getDeputyRuleTable().maxruleid; // 获取下一个可用的规则ID
        DeputyRuleTableItem deputyRuleTableItem = new DeputyRuleTableItem(deputyRuleId, deputyrule);
        MemConnect.getDeputyRuleTableList().add(deputyRuleTableItem);
        //根据是否为union类型来获取代理表源类id
        if(deputyType==2) {//union类
            // 遍历元组列表
            for (int i = 0; i < tupleNum; i++) {
                Tuple t = tpl.tuplelist.get(i);
                int classId = t.classId;
        
                // 如果当前的 classId 已经处理过，则跳过当前循环
                if (processedClassIds.contains(classId)) {
                    continue;
                }
                // 创建 DeputyTableItem 对象并添加到列表中
                DeputyTableItem deputyTableItem = new DeputyTableItem(classId, deputyId, deputyRuleId);
                MemConnect.getDeputyTableList().add(deputyTableItem);
        
                // 将当前的 classId 添加到 processedClassIds 中，表示已经处理过了
                processedClassIds.add(classId);
            }
            int attrNum = selectResult.getClassName().length; // 获取属性个数
            // 遍历已处理的类 ID 集合
            for (Integer classId : processedClassIds) {
                for (int i = 0; i < attrNum; i++) {
                    // 创建 SwitchingTableItem
                        String sourceAttrName = selectResult.aliasMapping.getOrDefault(
                            new AbstractMap.SimpleEntry<>(classId, i),
                            selectResult.getAlias()[i] // 默认使用 alias[i]
                        );

                    SwitchingTableItem switchingTableItem = new SwitchingTableItem(
                        classId, // 源类 ID
                        selectResult.getAttrid()[i], // 源类属性 ID
                        sourceAttrName, // 源类属性名称
                        deputyId, // 代理类 ID
                        i, // 代理类属性 ID
                        selectResult.getAttrname()[i], // 代理类属性名称
                        deputyrule[1]// 代理规则
                    );
                    MemConnect.getSwitchingTableList().add(switchingTableItem);
                }
            }
        }
        else if (deputyType==3) { //group
            String [] classNames = selectResult.getClassName();
            for (String className : classNames) {
                int originId = memConnect.getClassId(className);
                // 如果当前的 className 已经在 processedClassNames 中出现过，则跳过当前循环
                if (processedClassIds.contains(originId)) {
                    continue;
                }
                
                DeputyTableItem deputyTableItem = new DeputyTableItem(originId, deputyId, deputyRuleId);
                MemConnect.getDeputyTableList().add(deputyTableItem);

                // 将当前的 className 添加到 processedClassNames 中，表示已经处理过了
                processedClassIds.add(originId);
            }
            int attrNum = selectResult.getClassName().length;
            PlainSelect plainSelect = (PlainSelect) selectStmt.getSelectBody();
            List<SelectItem> selectItems = plainSelect.getSelectItems();
            String groupAttrName = plainSelect.getGroupBy().getGroupByExpressionList()
                    .getExpressions().get(0).toString();
            for (int i = 0; i < attrNum; i++) {
                String dptName = selectResult.getAttrname()[i];
                String oriName;
                String type;
                SelectExpressionItem selectItem = (SelectExpressionItem) selectItems.get(i);
                Expression expression = selectItem.getExpression();

                if (expression instanceof Function) {
                    Function function = (Function) expression;
                    type = function.getName().toLowerCase(Locale.ROOT);
                    Expression parameter = function.getParameters() == null
                            || function.getParameters().getExpressions().isEmpty()
                            ? null : function.getParameters().getExpressions().get(0);
                    if (function.isAllColumns() || parameter == null
                            || "*".equals(parameter.toString())) {
                        oriName = groupAttrName;
                    } else {
                        oriName = parameter instanceof Column
                                ? ((Column) parameter).getColumnName()
                                : parameter.toString();
                    }
                } else {
                    type = "groupdeputy";
                    oriName = expression instanceof Column
                            ? ((Column) expression).getColumnName()
                            : expression.toString();
                }
                 // 创建 SwitchingTableItem
                SwitchingTableItem switchingTableItem = new SwitchingTableItem(
                memConnect.getClassId(selectResult.getClassName()[i]), // 源类 ID
                selectResult.getAttrid()[i], // 源类属性 ID
                oriName, // 源类属性名称
                deputyId, // 代理类 ID
                i, // 代理类属性 ID
                dptName, // 代理类属性名称
                type // 代理规则
            );
            MemConnect.getSwitchingTableList().add(switchingTableItem);
            }
        }
        else {
            String [] classNames = selectResult.getClassName();
            for (String className : classNames) {
                int originId = memConnect.getClassId(className);
                // 如果当前的 className 已经在 processedClassNames 中出现过，则跳过当前循环
                if (processedClassIds.contains(originId)) {
                    continue;
                }
                if(deputyType==1){//join代理类，将连接条件存至deputyrule
                    ArrayList<String>joinColumnNames = selectResult.getJoinColumnNames();
                    if(joinColumnNames.size()!=0){
                        deputyrule[2] = String.join(",", joinColumnNames); 
                    }
                }
                DeputyTableItem deputyTableItem = new DeputyTableItem(originId, deputyId, deputyRuleId);
                MemConnect.getDeputyTableList().add(deputyTableItem);

                // 将当前的 className 添加到 processedClassNames 中，表示已经处理过了
                processedClassIds.add(originId);
            }
            int attrNum = selectResult.getClassName().length;
            for (int i = 0; i < attrNum; i++) {
                 // 创建 SwitchingTableItem
                SwitchingTableItem switchingTableItem = new SwitchingTableItem(
                memConnect.getClassId(selectResult.getClassName()[i]), // 源类 ID
                selectResult.getAttrid()[i], // 源类属性 ID
                selectResult.getAttrname()[i], // 源类属性名称
                deputyId, // 代理类 ID
                i, // 代理类属性 ID
                selectResult.getAttrname()[i], // 代理类属性名称
                deputyrule[1] // 代理规则
            );
            MemConnect.getSwitchingTableList().add(switchingTableItem);
            }
        }
    }

    /**
     * 插入元组，并新建BiPointerTableItem
     * @param selectResult 插入的元组列表
     * @param deputyId 新建代理类id
     * @param deputyType 新建代理类类型
     */
    private void createBiPointerTableItem(SelectResult selectResult, int deputyId, int deputyType) throws TMDBException, IOException {
        // 遍历 tuplelist 查找 tuple 对象，找到对应的 tupleId
        TupleList tpl = selectResult.getTpl();
        int objectNum = tpl.tuplenum;
        List<String> attrNamesList = Arrays.asList(selectResult.getAttrname());
        HashSet<String> processedClassNames = new HashSet<>();
        if (deputyType == 2) {  //union类建表方法 
            for (int i = 0; i < objectNum; i++) {
                Tuple t = tpl.tuplelist.get(i);
                InsertImpl insertImpl = new InsertImpl();
                int deputyObjectId = insertImpl.executeOne(deputyId, attrNamesList, new Tuple(
                    t.tupleSize, 
                    t.tupleId, 
                    t.classId, 
                    t.tupleIds.clone(), 
                    t.tuple.clone(), 
                    t.delete));
    
                // 创建 BiPointerTableItem 对象并设置相关属性
                // System.out.printf("classid = %d, objid = %d, dpt id = %d, dptobjid = %d\n", t.classId, t.tupleId, deputyId, deputyObjectId);
                BiPointerTableItem biPointerItem = new BiPointerTableItem(t.classId, t.tupleId, deputyId, deputyObjectId);
            
                // 使用 MemConnect.getBiPointerTableList().add() 插入 BiPointerTable
                MemConnect.getBiPointerTableList().add(biPointerItem);
            }
            }
        else if (deputyType == 3) { //groupby类
            String [] classNames = selectResult.getClassName();
            HashMap<Object, ArrayList<Integer>> groupMap = selectResult.getGroupMap();
            int groupColumnIndex = 0;
            for (SwitchingTableItem item : MemConnect.getSwitchingTableList()) {
                if (item.deputyId == deputyId && "groupdeputy".equals(item.rule)) {
                    groupColumnIndex = item.deputyAttrId;
                    break;
                }
            }
            for (String className : classNames) { //hash表对classid去重
                if (processedClassNames.contains(className)) {
                    continue;
                }
                processedClassNames.add(className);   
            }
            for (int i = 0; i < objectNum; i++) {
                Tuple t = tpl.tuplelist.get(i);
                InsertImpl insertImpl = new InsertImpl();
                int deputyObjectId = insertImpl.executeOne(deputyId, attrNamesList, new Tuple(
                    t.tupleSize, 
                    t.tupleId, 
                    t.classId, 
                    t.tupleIds.clone(), 
                    t.tuple.clone(), 
                    t.delete));
                Object o =t.tuple[groupColumnIndex];
                ArrayList<Integer> idList = groupMap.get(o);
                if (idList == null) {
                    continue;
                }
                // 创建 BiPointerTableItem 对象并设置相关
                Map<String, Integer> classTupleMap = new HashMap<>();
                for (int j = 0; j < classNames.length; j++) {
                classTupleMap.put(classNames[j], t.tupleIds[j]);
                }
                for(String className : processedClassNames) {
                    // 对于group代理，源类的多条可能对应代理类的一条
                    for(Integer oriObjectId : idList) {
                        int originId = memConnect.getClassId(className);
                        BiPointerTableItem biPointerItem = new BiPointerTableItem(originId, oriObjectId, deputyId, deputyObjectId);

                        // 使用 MemConnect.getBiPointerTableList().add() 插入 BiPointerTable
                        MemConnect.getBiPointerTableList().add(biPointerItem);
                    }
                }
            } 
        }
        else { //其它类建表方法
            String [] classNames = selectResult.getClassName();
            for (String className : classNames) { //hash表对classid去重
                if (processedClassNames.contains(className)) {
                    continue;
                }
                processedClassNames.add(className);   
            }
            for (int i = 0; i < objectNum; i++) {
                Tuple t = tpl.tuplelist.get(i);
                InsertImpl insertImpl = new InsertImpl();
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
        }
    }

    /**
     * 给定创建代理类语句，返回代理规则
     * @param stmt 创建代理类语句
     * @return 代理规则
     */
    private int getDeputyType(net.sf.jsqlparser.statement.create.deputyclass.CreateDeputyClass stmt) {
        switch (stmt.getType().toLowerCase(Locale.ROOT)) {
            case "selectdeputy":    return 0;
            case "joindeputy":      return 1;
            case "uniondeputy":     return 2;
            case "groupdeputy":   return 3;
        }
        return -1;
    }

    
    /**
     * 给定查询语句，返回select查询执行结果（创建deputyclass后面的select语句中的selectResult）
     * @param selectStmt select查询语句
     * @return 查询执行结果（包含所有满足条件元组）
     */
    private SelectResult getSelectResult(Select selectStmt) throws TMDBException, IOException {
        SelectImpl selectExecutor = new SelectImpl();
        return selectExecutor.select(selectStmt);
    }

    @SuppressWarnings("unused")
    private HashSet<Integer> getOriginClass(SelectResult selectResult) {
        ArrayList<String> collect = Arrays.stream(selectResult.getClassName()).collect(Collectors.toCollection(ArrayList::new));
        HashSet<String> collect1 = Arrays.stream(selectResult.getClassName()).collect(Collectors.toCollection(HashSet::new));
        HashSet<Integer> res = new HashSet<>();
        for (String s : collect1) {
            res.add(collect.indexOf(s));
        }
        return res;
    }
}



        
            
