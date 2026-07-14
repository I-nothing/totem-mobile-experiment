package edu.whu.tmdb.query.operations.impl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import edu.whu.tmdb.query.operations.Exception.ErrorList;
import edu.whu.tmdb.query.operations.Exception.TMDBException;
import edu.whu.tmdb.query.operations.utils.MemConnect;
import edu.whu.tmdb.storage.memory.MemManager;
import edu.whu.tmdb.storage.memory.Tuple;
import edu.whu.tmdb.storage.memory.SystemTable.BiPointerTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.ClassTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.ObjectTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.SwitchingTableItem;

public class ImpreciseDeputyManager {

    private static final MemConnect memConnect = MemConnect.getInstance(MemManager.getInstance());

    /**
     * 预校验 INSERT ... INTO deputyClass 的合法性。
     * 在源元组插入之前调用，若非法则抛出异常阻止插入。
     *
     * @return 目标非严格代理类 ID；若 SQL 不含 INTO 则返回 -1
     */
    public static int validateIntoDeputy(String rawSql, int sourceClassId) throws TMDBException {
        // 1. 解析 INTO deputyClass
        String deputyClassName = parseIntoDeputyClass(rawSql);
        if (deputyClassName == null) {
            return -1; // 没有 INTO，普通插入
        }

        // 2. 检查代理类是否存在
        int deputyClassId;
        try {
            deputyClassId = memConnect.getClassId(deputyClassName);
        } catch (Exception e) {
            throw new TMDBException(ErrorList.CLASS_NAME_DOES_NOT_EXIST,
                    "代理类 " + deputyClassName + " 不存在");
        }

        // 3. 检查是否为非严格 SelectDeputy (classtype == 4)
        int classType = getClassType(deputyClassId);
        if (classType != 4) {
            throw new TMDBException(ErrorList.TYPE_IS_NOT_SUPPORTED,
                    "不允许对严格代理类 " + deputyClassName + " 使用 INTO");
        }

        // 4. 检查是否为源类的合法代理类
        List<Integer> deputyIds = memConnect.getDeputyIdList(sourceClassId);
        if (!deputyIds.contains(deputyClassId)) {
            throw new TMDBException(ErrorList.TYPE_IS_NOT_SUPPORTED,
                    deputyClassName + " 不是当前源类的代理类");
        }

        return deputyClassId;
    }

    /**
     * 执行非严格代理类的显式插入 —— 将源元组副本及双向指针写入代理类。
     * 必须在源元组已插入之后调用。
     */
    public static void executeDeputyInsert(int sourceClassId, int sourceTupleId, int deputyClassId)
            throws TMDBException {
        // 1. 获取源元组
        Tuple sourceTuple = memConnect.GetTuple(sourceTupleId);
        if (sourceTuple == null) {
            throw new TMDBException(ErrorList.CLASS_ID_DOES_NOT_EXIST, sourceTupleId);
        }

        // 2. 按代理类属性顺序构造代理元组
        int deputyAttrNum = memConnect.getClassAttrnum(deputyClassId);
        Object[] deputyValues = new Object[deputyAttrNum];
        int mappingCount = 0;
        for (SwitchingTableItem sti : MemConnect.getSwitchingTableList()) {
            if (sti.oriId == sourceClassId && sti.deputyId == deputyClassId) {
                int oriAttrIdx = memConnect.getAttrid(sourceClassId, sti.oriAttr);
                if (oriAttrIdx >= 0 && oriAttrIdx < sourceTuple.tuple.length) {
                    deputyValues[sti.deputyAttrId] = sourceTuple.tuple[oriAttrIdx];
                    mappingCount++;
                }
            }
        }
        if (mappingCount == 0) {
            throw new TMDBException(ErrorList.TYPE_IS_NOT_SUPPORTED,
                    "No attribute mapping found for deputy insert");
        }

        // 3. 插入代理元组
        int deputyTupleId = MemConnect.getObjectTable().maxTupleId++;
        Tuple deputyTuple = new Tuple();
        deputyTuple.tupleSize = deputyValues.length;
        deputyTuple.tupleId = deputyTupleId;
        deputyTuple.classId = deputyClassId;
        deputyTuple.tupleIds = new int[]{sourceTupleId};
        deputyTuple.tuple = deputyValues;
        deputyTuple.delete = false;

        memConnect.InsertTuple(deputyTuple);
        MemConnect.getObjectTableList().add(new ObjectTableItem(deputyClassId, deputyTupleId));

        // 4. 建立双向指针
        MemConnect.getBiPointerTableList().add(
                new BiPointerTableItem(sourceClassId, sourceTupleId, deputyClassId, deputyTupleId));
    }

    /**
     * 处理非严格代理类的显式插入（INSERT ... INTO deputyClass）。
     * 保留用于向后兼容，内部拆分为校验 + 执行两步。
     */
    public static void handleImpreciseInsert(String rawSql, int sourceClassId, int sourceTupleId)
            throws TMDBException {
        int deputyClassId = validateIntoDeputy(rawSql, sourceClassId);
        if (deputyClassId >= 0) {
            executeDeputyInsert(sourceClassId, sourceTupleId, deputyClassId);
        }
    }

    /**
     * 处理非严格代理类的同步删除
     */
    public static void handleImpreciseDelete(int sourceClassId, int sourceTupleId) {
        try {
            List<BiPointerTableItem> toRemove = new ArrayList<>();
            for (BiPointerTableItem bpi : MemConnect.getBiPointerTableList()) {
                if (bpi.objectid == sourceTupleId && getClassType(bpi.deputyid) == 4) {
                    toRemove.add(bpi);
                }
            }

            for (BiPointerTableItem bpi : toRemove) {
                memConnect.DeleteTuple(bpi.deputyobjectid);
                Iterator<ObjectTableItem> it = MemConnect.getObjectTableList().iterator();
                while (it.hasNext()) {
                    if (it.next().tupleid == bpi.deputyobjectid) {
                        it.remove();
                        break;
                    }
                }
                MemConnect.getBiPointerTableList().remove(bpi);
            }
        } catch (Exception e) {
            System.err.println("===== EXCEPTION in handleImpreciseDelete =====");
            e.printStackTrace();
        }
    }

    /**
     * 处理非严格代理类的同步更新
     */
    public static void handleImpreciseUpdate(int sourceClassId, int sourceTupleId) {
        try {
            Tuple sourceTuple = memConnect.GetTuple(sourceTupleId);
            if (sourceTuple == null) return;

            for (BiPointerTableItem bpi : MemConnect.getBiPointerTableList()) {
                if (bpi.objectid == sourceTupleId && getClassType(bpi.deputyid) == 4) {
                    int deputyClassId = bpi.deputyid;
                    int deputyTupleId = bpi.deputyobjectid;
                    Tuple deputyTuple = memConnect.GetTuple(deputyTupleId);
                    if (deputyTuple == null) continue;

                    for (SwitchingTableItem sti : MemConnect.getSwitchingTableList()) {
                        if (sti.oriId == sourceClassId && sti.deputyId == deputyClassId) {
                            int oriAttrIdx = memConnect.getAttrid(sourceClassId, sti.oriAttr);
                            int dptAttrIdx = memConnect.getAttrid(deputyClassId, sti.deputyAttr);
                            if (oriAttrIdx >= 0 && oriAttrIdx < sourceTuple.tuple.length &&
                                    dptAttrIdx >= 0 && dptAttrIdx < deputyTuple.tuple.length) {
                                deputyTuple.tuple[dptAttrIdx] = sourceTuple.tuple[oriAttrIdx];
                            }
                        }
                    }
                    memConnect.UpateTuple(deputyTuple, deputyTupleId);
                }
            }
        } catch (Exception e) {
            System.err.println("===== EXCEPTION in handleImpreciseUpdate =====");
            e.printStackTrace();
        }
    }

    // 从 SQL 末尾提取 INTO deputyClass
    private static String parseIntoDeputyClass(String rawSql) {
        if (rawSql == null) return null;
        Pattern pattern = Pattern.compile("(?i)\\s+INTO\\s+(\\w+)\\s*;?\\s*$");
        Matcher matcher = pattern.matcher(rawSql);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    // 获取类的 classtype
    private static int getClassType(int classId) {
        for (ClassTableItem item : MemConnect.getClassTableList()) {
            if (item.classid == classId) {
                return item.classtype;
            }
        }
        return -1;
    }
}