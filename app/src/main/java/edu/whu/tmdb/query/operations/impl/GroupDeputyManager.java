package edu.whu.tmdb.query.operations.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.whu.tmdb.query.operations.Exception.ErrorList;
import edu.whu.tmdb.query.operations.Exception.TMDBException;
import edu.whu.tmdb.query.operations.utils.MemConnect;
import edu.whu.tmdb.query.operations.utils.SelectResult;
import edu.whu.tmdb.storage.memory.MemManager;
import edu.whu.tmdb.storage.memory.Tuple;
import edu.whu.tmdb.storage.memory.SystemTable.BiPointerTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.DeputyRuleTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.DeputyTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.ObjectTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.SwitchingTableItem;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;

/** Keeps GroupDeputy rows and pointers consistent with their saved SELECT rule. */
public final class GroupDeputyManager {
    private GroupDeputyManager() {}

    public static void onSourceInsert(int sourceClassId, int tupleId)
            throws TMDBException, IOException {
        refreshRelatedDeputies(sourceClassId);
    }

    public static void onSourceDelete(int sourceClassId, int tupleId)
            throws TMDBException, IOException {
        refreshRelatedDeputies(sourceClassId);
    }

    public static void onSourceUpdate(int sourceClassId, int tupleId)
            throws TMDBException, IOException {
        refreshRelatedDeputies(sourceClassId);
    }

    private static void refreshRelatedDeputies(int sourceClassId)
            throws TMDBException, IOException {
        Set<Integer> deputyIds = new HashSet<>();
        for (DeputyTableItem item : new ArrayList<>(MemConnect.getDeputyTableList())) {
            if (item.originid == sourceClassId && isGroupDeputy(item.ruleid)) {
                deputyIds.add(item.deputyid);
            }
        }
        for (int deputyId : deputyIds) {
            rebuildDeputy(sourceClassId, deputyId);
        }
    }

    private static boolean isGroupDeputy(int ruleId) {
        DeputyRuleTableItem rule = findRule(ruleId);
        return rule != null && rule.deputyrule.length > 1
                && "groupdeputy".equalsIgnoreCase(rule.deputyrule[1]);
    }

    private static void rebuildDeputy(int sourceClassId, int deputyId)
            throws TMDBException, IOException {
        String sql = findSelectSql(sourceClassId, deputyId);
        SelectResult desired = executeSelect(sql);
        MemConnect memConnect = MemConnect.getInstance(MemManager.getInstance());

        List<Integer> oldTupleIds = new ArrayList<>();
        for (ObjectTableItem item : MemConnect.getObjectTableList()) {
            if (item.classid == deputyId) oldTupleIds.add(item.tupleid);
        }
        for (int tupleId : oldTupleIds) memConnect.DeleteTuple(tupleId);
        MemConnect.getObjectTableList().removeIf(item -> item.classid == deputyId);
        MemConnect.getBiPointerTableList().removeIf(item -> item.deputyid == deputyId);

        if (desired == null || desired.getTpl() == null) return;

        String deputyName = memConnect.getClassName(deputyId);
        List<String> columns = memConnect.getColumns(deputyName);
        int groupColumnIndex = getGroupColumnIndex(deputyId);
        InsertImpl insert = new InsertImpl();
        for (Tuple tuple : desired.getTpl().tuplelist) {
            int deputyTupleId = insert.executeOne(deputyId, columns, copyTuple(tuple));
            Object groupKey = tuple.tuple[groupColumnIndex];
            ArrayList<Integer> sourceTupleIds = desired.getGroupMap().get(groupKey);
            if (sourceTupleIds == null) continue;
            for (int sourceTupleId : sourceTupleIds) {
                MemConnect.getBiPointerTableList().add(new BiPointerTableItem(
                        sourceClassId, sourceTupleId, deputyId, deputyTupleId));
            }
        }
    }

    private static Tuple copyTuple(Tuple tuple) {
        int[] tupleIds = tuple.tupleIds == null ? null : tuple.tupleIds.clone();
        return new Tuple(tuple.tupleSize, tuple.tupleId, tuple.classId,
                tupleIds, tuple.tuple.clone(), tuple.delete);
    }

    private static int getGroupColumnIndex(int deputyId) {
        for (SwitchingTableItem item : MemConnect.getSwitchingTableList()) {
            if (item.deputyId == deputyId && "groupdeputy".equals(item.rule)) {
                return item.deputyAttrId;
            }
        }
        return 0;
    }

    private static String findSelectSql(int sourceClassId, int deputyId) throws TMDBException {
        for (DeputyTableItem item : MemConnect.getDeputyTableList()) {
            if (item.originid != sourceClassId || item.deputyid != deputyId) continue;
            DeputyRuleTableItem rule = findRule(item.ruleid);
            if (rule != null && rule.deputyrule.length > 0) return rule.deputyrule[0];
        }
        throw new TMDBException(ErrorList.CLASS_ID_DOES_NOT_EXIST, deputyId);
    }

    private static DeputyRuleTableItem findRule(int ruleId) {
        for (DeputyRuleTableItem item : MemConnect.getDeputyRuleTableList()) {
            if (item.ruleid == ruleId) return item;
        }
        return null;
    }

    private static SelectResult executeSelect(String sql) throws TMDBException, IOException {
        try {
            Statement statement = CCJSqlParserUtil.parse(
                    new ByteArrayInputStream(sql.getBytes()));
            return new SelectImpl().select(statement);
        } catch (JSQLParserException e) {
            throw new TMDBException(ErrorList.TYPE_IS_NOT_SUPPORTED,
                    "saved GroupDeputy rule: " + sql);
        }
    }
}
