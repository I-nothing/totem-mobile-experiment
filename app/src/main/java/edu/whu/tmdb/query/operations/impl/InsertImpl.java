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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import edu.whu.tmdb.query.Transaction;
import edu.whu.tmdb.query.operations.Insert;
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
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.expression.StringValue;

public class InsertImpl implements Insert {
    private MemConnect memConnect;

    ArrayList<Integer> tupleIdList = new ArrayList<>();
    public static String lastRawSql = null;  // 保存原始 SQL

    public InsertImpl() {
        this.memConnect = MemConnect.getInstance(MemManager.getInstance());
    }

    @Override
    public ArrayList<Integer> insert(Statement stmt) throws TMDBException, IOException {
        tupleIdList.clear();

        net.sf.jsqlparser.statement.insert.Insert insertStmt = (net.sf.jsqlparser.statement.insert.Insert) stmt;
        Table table = insertStmt.getTable();
        List<String> attrNames = new ArrayList<>();
        if (insertStmt.getColumns() == null) {
            attrNames = memConnect.getColumns(table.getName());
        } else {
            int insertColSize = insertStmt.getColumns().size();
            for (int i = 0; i < insertColSize; i++) {
                attrNames.add(insertStmt.getColumns().get(i).getColumnName());
            }
        }

        TupleList tupleList;
        String rawSql = lastRawSql != null ? lastRawSql : stmt.toString();
        lastRawSql = null;  // 用完清空

        if (rawSql.toUpperCase().contains("VALUES")) {
            tupleList = parseValuesFromSql(rawSql, attrNames.size());
        } else if (insertStmt.getSelect() != null) {
            SelectImpl select = new SelectImpl();
            SelectResult selectResult = select.select(insertStmt.getSelect());
            if (selectResult != null) {
                tupleList = selectResult.getTpl();
            } else {
                tupleList = new TupleList();
            }
        } else {
            tupleList = new TupleList();
        }

        // 任务一：非严格代理显式插入 — 先校验 INTO，再执行源插入
        int sourceClassId = memConnect.getClassId(table.getName());

        // 预校验 INTO 子句（在源元组插入之前），非法则抛出异常
        int deputyClassId = ImpreciseDeputyManager.validateIntoDeputy(rawSql, sourceClassId);

        // 执行源表插入
        execute(table.getName(), attrNames, tupleList);

        // 若指定了 INTO，执行代理类插入
        if (deputyClassId >= 0) {
            for (int newTupleId : tupleIdList) {
                ImpreciseDeputyManager.executeDeputyInsert(sourceClassId, newTupleId, deputyClassId);
            }
        }
        if (!tupleIdList.isEmpty()) {
            GroupDeputyManager.onSourceInsert(sourceClassId, tupleIdList.get(tupleIdList.size() - 1));
        }

        return tupleIdList;
    }

    public void execute(String tableName, List<String> columns, TupleList tupleList) throws TMDBException, IOException {
        int classId = memConnect.getClassId(tableName);
        int attrNum = memConnect.getClassAttrnum(tableName);
        int[] attrIdList = memConnect.getAttridList(classId, columns);
        for (Tuple tuple : tupleList.tuplelist) {
            if (tuple.tuple.length != columns.size()) {
                throw new TMDBException();
            }
            tupleIdList.add(insertOne(classId, columns, tuple, attrNum, attrIdList));
        }
    }

    public void execute(int classId, List<String> columns, TupleList tupleList) throws TMDBException, IOException {
        int attrNum = memConnect.getClassAttrnum(classId);
        int[] attrIdList = memConnect.getAttridList(classId, columns);
        for (Tuple tuple : tupleList.tuplelist) {
            if (tuple.tuple.length != columns.size()) {
                throw new TMDBException();
            }
            tupleIdList.add(insertOne(classId, columns, tuple, attrNum, attrIdList));
        }
    }

    public int executeOne(int classId, List<String> columns, Tuple tuple) throws TMDBException, IOException {
        int attrNum = memConnect.getClassAttrnum(classId);
        int[] attridList = memConnect.getAttridList(classId, columns);

        if (tuple.tuple.length != columns.size()) {
            throw new TMDBException();
        }
        int tupleId = insertOne(classId, columns, tuple, attrNum, attridList);
        tupleIdList.add(tupleId);
        return tupleId;
    }

    public String deputyquery(int classid, int deputyid) {
        for (SwitchingTableItem switchingTableItem : MemConnect.getSwitchingTableList()) {
            if (switchingTableItem.oriId == classid && switchingTableItem.deputyId == deputyid)
                return switchingTableItem.rule;
        }
        return "";
    }

    public int repeattuple(int classid, Tuple checktuple) {
        int num = 0;
        int tuplelen = checktuple.tupleSize;
        TupleList classtuplelisty = memConnect.getTupleList(classid);
        int flag = 1;
        for (Tuple temptuple : classtuplelisty.tuplelist) {
            flag = 1;
            for (int i = 0; i < tuplelen; i++) {
                if (!temptuple.tuple[i].equals(checktuple.tuple[i])) {
                    flag = 0;
                    break;
                }
            }
            if (flag == 1)
                num++;
        }
        return num;
    }

    public static String getdeputyrule(int deputyclassid, int index) {
        for (DeputyTableItem tempitem : MemConnect.getDeputyTableList()) {
            if (tempitem.deputyid == deputyclassid)
                for (DeputyRuleTableItem tempruleitem : MemConnect.getDeputyRuleTableList()) {
                    if (tempruleitem.ruleid == tempitem.ruleid)
                        return tempruleitem.deputyrule[index];
                }
        }
        return "";
    }

    public TupleList except2(TupleList ltpl, TupleList rtpl) {
        TupleList tupleList = new TupleList();
        List<Tuple> set = new ArrayList<>();
        for (Tuple tuple : ltpl.tuplelist) {
            set.add(tuple);
        }
        int len = set.size();
        for (Tuple tuple : rtpl.tuplelist) {
            for (int i = 0; i < len; i++) {
                if (tuple.equals(set.get(i))) {
                    set.remove(i);
                    len--;
                    break;
                }
            }
        }
        tupleList.tuplelist = set;
        tupleList.tuplenum = set.size();
        return tupleList;
    }

    public TupleList except(TupleList ltpl, TupleList rtpl) {
        TupleList tupleList = new TupleList();
        List<Tuple> set = new ArrayList<>();
        for (Tuple tuple : ltpl.tuplelist) {
            set.add(tuple);
        }
        int len = set.size();
        for (Tuple tuple : rtpl.tuplelist) {
            for (int i = 0; i < len; i++) {
                if (Arrays.toString(tuple.tuple).equals(Arrays.toString(set.get(i).tuple))) {
                    len--;
                    set.remove(i);
                    break;
                }
            }
        }
        tupleList.tuplelist = set;
        tupleList.tuplenum = set.size();
        return tupleList;
    }

    public String getDeputyAttr(int classid, int deputyid) {
        for (SwitchingTableItem switchingTableItem : MemConnect.getSwitchingTableList()) {
            if (switchingTableItem.oriId == classid && switchingTableItem.deputyId == deputyid)
                return switchingTableItem.oriAttr;
        }
        return "";
    }

    private Integer insertOne(int classId, List<String> columns, Tuple tuple, int attrNum, int[] attrId) throws TMDBException, IOException {
        int tupleid = MemConnect.getObjectTable().maxTupleId++;

        Object[] temp = new Object[attrNum];
        for (int i = 0; i < attrId.length; i++) {
            temp[attrId[i]] = tuple.tuple[i];
        }
        tuple.setTuple(tuple.tuple.length, tupleid, classId, tuple.tupleIds, temp);
        memConnect.InsertTuple(tuple);
        MemConnect.getObjectTableList().add(new ObjectTableItem(classId, tupleid));

        ArrayList<Integer> DeputyIdList = memConnect.getDeputyIdList(classId);

        // 跳过非严格代理类（classtype==4），避免自动填充
        Iterator<Integer> dptIt = DeputyIdList.iterator();
        while (dptIt.hasNext()) {
            int deputyId = dptIt.next();
            String deputyTypeStr = InsertImpl.getdeputyrule(deputyId, 1);
            if ("impreciseselectdeputy".equals(deputyTypeStr)
                    || "groupdeputy".equals(deputyTypeStr)) {
                dptIt.remove();
            }
        }

        if (!DeputyIdList.isEmpty()) {
            for (int deputyClassId : DeputyIdList) {
                String stmt = getdeputyrule(deputyClassId, 0);
                Transaction transaction = Transaction.getInstance();
                SelectResult selectResult = null;
                String deputyname = memConnect.getClassName(deputyClassId);
                if (Objects.equals(deputyquery(classId, deputyClassId), "joindeputy")) {
                    try {
                        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(stmt.getBytes());
                        Statement selectstmt = CCJSqlParserUtil.parse(byteArrayInputStream);
                        TupleList inserttupleList = new TupleList();
                        inserttupleList.addTuple(tuple);
                        selectResult = transaction.joinquery("", -1, selectstmt, inserttupleList);
                    } catch (JSQLParserException e) {
                        System.out.println("syntax error");
                    }
                    if (selectResult == null) {
                        System.out.println("selectResult is null in join branch, skipping " + deputyname);
                        continue;
                    }
                    TupleList newDeputyTpl = selectResult.getTpl();
                    String[] classNames = selectResult.getClassName();
                    HashSet<String> processedClassNames = new HashSet<>();
                    if (newDeputyTpl.tuplelist.size() != 0) {
                        for (String className : classNames) {
                            if (processedClassNames.contains(className)) {
                                continue;
                            }
                            processedClassNames.add(className);
                        }
                        for (Tuple inserttuple : newDeputyTpl.tuplelist) {
                            int inserttupleid = executeOne(deputyClassId, memConnect.getColumns(deputyname), inserttuple);
                            Map<String, Integer> classTupleMap = new HashMap<>();
                            for (int j = 0; j < classNames.length; j++) {
                                classTupleMap.put(classNames[j], inserttuple.tupleIds[j]);
                            }
                            for (String className : processedClassNames) {
                                int originId = memConnect.getClassId(className);
                                BiPointerTableItem biPointerItem = new BiPointerTableItem(originId, classTupleMap.get(className), deputyClassId, inserttupleid);
                                MemConnect.getBiPointerTableList().add(biPointerItem);
                            }
                        }
                    }
                } else if (Objects.equals(deputyquery(classId, deputyClassId), "groupdeputy")) {
                    String groupAttr = getDeputyAttr(classId, deputyClassId);
                    int groupAttrIdx = memConnect.getAttrid(classId, groupAttr);
                    Object groupObj = temp[groupAttrIdx];
                    ArrayList<String> funcNames = new ArrayList<>();
                    ArrayList<Integer> pIndexs = new ArrayList<>();
                    for (SwitchingTableItem sti : MemConnect.getSwitchingTableList()) {
                        if (classId == sti.oriId && deputyClassId == sti.deputyId) {
                            if (!Objects.equals(sti.oriAttr, sti.deputyAttr)) {
                                funcNames.add(sti.rule);
                                pIndexs.add(memConnect.getAttrid(classId, sti.oriAttr));
                            }
                        }
                    }

                    try {
                        String sql = "select * from " + memConnect.getClassName(classId) + " where " + groupAttr + " = " + groupObj.toString() + ";";
                        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(sql.getBytes());
                        Statement selectstmt = CCJSqlParserUtil.parse(byteArrayInputStream);
                        selectResult = transaction.query("", -1, selectstmt);
                    } catch (JSQLParserException e) {
                        System.out.println("syntax error");
                    }
                    if (selectResult == null) {
                        System.out.println("selectResult is null in group branch, skipping " + deputyname);
                        continue;
                    }
                    TupleList changedList = selectResult.getTpl();

                    SelectImpl selecttemp = new SelectImpl();
                    Tuple t = selecttemp.aggOne(groupObj, changedList, funcNames, pIndexs);

                    if (changedList.tuplenum == 1) {
                        int inserttupleid = executeOne(deputyClassId, memConnect.getColumns(deputyname), t);
                        MemConnect.getBiPointerTableList().add(new BiPointerTableItem(classId, tupleid, deputyClassId, inserttupleid));
                    } else {
                        TupleList oldDeputyTpl = memConnect.getTupleList(deputyClassId);
                        int groupObjDptId = -1;
                        Tuple oldt = null;
                        for (Tuple tempt : oldDeputyTpl.tuplelist) {
                            if (Objects.equals(tempt.tuple[0], groupObj)) {
                                groupObjDptId = tempt.tupleId;
                                oldt = tempt;
                            }
                        }
                        UpdateImpl updatetemp = new UpdateImpl();
                        updatetemp.updateOne(oldt, t);
                        MemConnect.getBiPointerTableList().add(new BiPointerTableItem(classId, tupleid, deputyClassId, groupObjDptId));
                    }
                } else {
                    try {
                        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(stmt.getBytes());
                        Statement selectstmt = CCJSqlParserUtil.parse(byteArrayInputStream);
                        selectResult = transaction.query("", -1, selectstmt);
                    } catch (JSQLParserException e) {
                        System.out.println("syntax error");
                    }
                    if (selectResult == null) {
                        System.out.println("selectResult is null in else branch, skipping " + deputyname);
                        continue;
                    }
                    TupleList oldDeputyTpl = memConnect.getTupleList(deputyClassId);
                    TupleList queryTpl = selectResult.getTpl();
                    TupleList insertTpl = new TupleList();
                    insertTpl = except2(queryTpl, oldDeputyTpl);
                    if (insertTpl.tuplelist.size() != 0) {
                        for (Tuple inserttuple : insertTpl.tuplelist) {
                            int inserttupleid = executeOne(deputyClassId, memConnect.getColumns(deputyname), inserttuple);
                            MemConnect.getBiPointerTableList().add(new BiPointerTableItem(classId, tupleid, deputyClassId, inserttupleid));
                        }
                    }
                }
            }
        }
        return tupleid;
    }

    private HashMap<String, String> getAttrNameHashMap(int originClassId, int deputyClassId, List<String> originColumns) {
        HashMap<String, String> attrNameHashMap = new HashMap<>();
        for (SwitchingTableItem switchingTableItem : MemConnect.getSwitchingTableList()) {
            if (switchingTableItem.oriId != originClassId || switchingTableItem.deputyId != deputyClassId) {
                continue;
            }
            for (String originColumn : originColumns) {
                if (switchingTableItem.oriAttr.equals(originColumn)) {
                    attrNameHashMap.put(originColumn, switchingTableItem.deputyAttr);
                }
            }
        }
        return attrNameHashMap;
    }

    private List<String> getDeputyColumns(HashMap<String, String> attrNameHashMap, List<String> originColumns) {
        List<String> deputyColumns = new ArrayList<>();
        for (String originColumn : originColumns) {
            if (attrNameHashMap.containsKey(originColumn)) {
                deputyColumns.add(attrNameHashMap.get(originColumn));
            }
        }
        return deputyColumns;
    }

    private Tuple getDeputyTuple(HashMap<String, String> attrNameHashMap, Tuple originTuple, List<String> originColumns) {
        Tuple deputyTuple = new Tuple();
        Object[] temp = new Object[attrNameHashMap.size()];
        int i = 0;
        for (String originColumn : originColumns) {
            if (attrNameHashMap.containsKey(originColumn)) {
                temp[i] = originTuple.tuple[originColumns.indexOf(originColumn)];
                i++;
            }
        }
        deputyTuple.tuple = temp;
        return deputyTuple;
    }

    private TupleList parseValuesFromSql(String sql, int colSize) {
        TupleList tupleList = new TupleList();
        int valuesIdx = sql.toUpperCase().indexOf("VALUES");
        if (valuesIdx == -1) return tupleList;

        String valuesPart = sql.substring(valuesIdx + 6);
        Pattern pattern = Pattern.compile("\\(([^)]+)\\)");
        Matcher matcher = pattern.matcher(valuesPart);

        while (matcher.find()) {
            String tupleContent = matcher.group(1);
            String[] parts = tupleContent.split(",");

            Object[] values = new Object[colSize];
            for (int i = 0; i < parts.length && i < colSize; i++) {
                String part = parts[i].trim();
                if ((part.startsWith("'") && part.endsWith("'")) ||
                        (part.startsWith("\"") && part.endsWith("\""))) {
                    values[i] = part.substring(1, part.length() - 1);
                } else {
                    try {
                        values[i] = Integer.parseInt(part);
                    } catch (NumberFormatException e1) {
                        try {
                            values[i] = Double.parseDouble(part);
                        } catch (NumberFormatException e2) {
                            values[i] = part;
                        }
                    }
                }
            }

            Tuple tuple = new Tuple();
            tuple.tuple = values;
            tuple.tupleSize = colSize;
            tuple.tupleIds = new int[0];
            tupleList.addTuple(tuple);
        }
        return tupleList;
    }
}
