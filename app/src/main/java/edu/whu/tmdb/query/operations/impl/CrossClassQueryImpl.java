package edu.whu.tmdb.query.operations.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.whu.tmdb.query.operations.Exception.ErrorList;
import edu.whu.tmdb.query.operations.Exception.TMDBException;
import edu.whu.tmdb.query.operations.utils.MemConnect;
import edu.whu.tmdb.query.operations.utils.SelectResult;
import edu.whu.tmdb.storage.memory.MemManager;
import edu.whu.tmdb.storage.memory.Tuple;
import edu.whu.tmdb.storage.memory.TupleList;
import edu.whu.tmdb.storage.memory.SystemTable.AttributeTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.BiPointerTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.DeputyTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.ObjectTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.SwitchingTableItem;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.statement.select.CrossClassPathExpression;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.schema.Table;

/**
 * 跨类查询实现。由 JSqlParser 解析 SQL 语法树，本类仅负责执行查询逻辑。
 * <p>
 * 支持路径表达式语法：
 * <pre>
 * SELECT Study_Progress{date > '2019-04-20'} -> Words -> Words_Audio_Pic -> Pic.picUrl FROM Study_Progress;
 * </pre>
 * <p>
 * 执行流程：
 * 1. 从 JSqlParser 解析的语法树中提取路径步骤、筛选条件和目标属性。
 * 2. 从起点类的所有对象开始，依次根据双向指针找到下一类的关联对象。
 * 3. 在每一层应用筛选条件（如果存在），过滤不符合条件的对象。
 * 4. 到达终点类后，取出目标属性值，作为查询结果。
 * <p>
 * 支持 AND/OR 复合筛选条件，支持虚属性解析。
 */
public class CrossClassQueryImpl {

    private final MemConnect memConnect;

    public CrossClassQueryImpl() {
        this.memConnect = MemConnect.getInstance(MemManager.getInstance());
    }

    // ===== 公开入口 =====

    /**
     * 执行跨类查询（使用 JSqlParser 解析的语法树）。
     *
     * @param plainSelect JSqlParser 解析后的 PlainSelect 语法树
     * @param pathExpr    跨类路径表达式
     * @return 查询结果
     * @throws TMDBException 查询过程中的异常
     */
    public SelectResult execute(PlainSelect plainSelect, CrossClassPathExpression pathExpr) throws TMDBException {
        // 从 FROM 子句获取起始类名
        String fromClassName = extractFromClassName(plainSelect);
        String startClassName = pathExpr.getStartClassName();

        // 验证 FROM 类与路径起始类一致
        if (!startClassName.equals(fromClassName)) {
            throw new TMDBException(ErrorList.CLASS_NAME_DOES_NOT_EXIST,
                    "路径起始类 '" + startClassName + "' 与 FROM 子句 '" + fromClassName + "' 不匹配");
        }

        // 从 JSqlParser 的 PathStep 转换为内部执行步骤
        List<ExecStep> steps = convertSteps(pathExpr);

        // 预验证整个路径（类存在性、代理关系存在性）
        validatePath(steps);

        // 获取起始类的所有对象
        int startClassId = memConnect.getClassId(startClassName);
        List<Tuple> currentTuples = getClassObjects(startClassId);
        int currentClassId = startClassId;

        String targetAttr = pathExpr.getTargetAttribute();

        // 遍历路径的每一步
        for (int stepIdx = 0; stepIdx < steps.size(); stepIdx++) {
            ExecStep step = steps.get(stepIdx);
            int targetClassId = memConnect.getClassId(step.className);

            if (stepIdx == 0) {
                // 第一步：起始类，应用过滤条件
                if (step.filterCondition != null && !step.filterCondition.isEmpty()) {
                    currentTuples = applyCompoundFilter(currentTuples, currentClassId, step.filterCondition);
                }
                if (steps.size() == 1 && targetAttr != null) {
                    return buildResult(currentTuples, targetClassId, targetAttr);
                }
                continue;
            }

            // 导航到下一个类
            currentTuples = navigate(currentClassId, currentTuples, targetClassId);
            currentClassId = targetClassId;

            // 应用过滤条件
            if (step.filterCondition != null && !step.filterCondition.isEmpty()) {
                currentTuples = applyCompoundFilter(currentTuples, currentClassId, step.filterCondition);
            }

            // 如果是最后一步且有目标属性
            if (stepIdx == steps.size() - 1 && targetAttr != null) {
                return buildResult(currentTuples, targetClassId, targetAttr);
            }
        }

        if (currentTuples.isEmpty()) {
            throw new TMDBException(ErrorList.COLUMN_NAME_DOES_NOT_EXIST, "查询结果为空");
        }

        // 返回最后一个类的所有属性
        return buildResult(currentTuples, currentClassId, null);
    }

    /**
     * 从 PlainSelect 的 FROM 子句中提取类名。
     */
    private String extractFromClassName(PlainSelect plainSelect) throws TMDBException {
        if (plainSelect.getFromItem() == null) {
            throw new TMDBException(ErrorList.MISSING_FROM_CLAUSE);
        }
        if (plainSelect.getFromItem() instanceof Table) {
            return ((Table) plainSelect.getFromItem()).getName();
        }
        return plainSelect.getFromItem().toString();
    }

    /**
     * 将 JSqlParser 的 PathStep 列表转换为内部 ExecStep 列表。
     */
    private List<ExecStep> convertSteps(CrossClassPathExpression pathExpr) throws TMDBException {
        List<ExecStep> steps = new ArrayList<>();
        for (CrossClassPathExpression.PathStep ps : pathExpr.getSteps()) {
            ExecStep step = new ExecStep();
            step.className = ps.getClassName();

            // 验证类是否存在
            if (!memConnect.classExist(step.className)) {
                throw new TMDBException(ErrorList.CLASS_NAME_DOES_NOT_EXIST, step.className);
            }

            // 将 Expression 条件转换为字符串
            if (ps.getCondition() != null) {
                step.filterCondition = ps.getCondition().toString();
            }

            steps.add(step);
        }

        // 验证目标属性（如果指定且不是 *）
        String targetAttr = pathExpr.getTargetAttribute();
        if (targetAttr != null && !targetAttr.isEmpty() && !steps.isEmpty()) {
            String lastClassName = steps.get(steps.size() - 1).className;
            int classId = memConnect.getClassId(lastClassName);
            if (!attributeExists(classId, targetAttr)) {
                throw new TMDBException(ErrorList.COLUMN_NAME_DOES_NOT_EXIST,
                        lastClassName + "." + targetAttr);
            }
        }

        return steps;
    }

    // ===== 路径验证 =====

    /**
     * 预验证整个路径：检查每一对相邻类之间是否存在代理关系。
     */
    private void validatePath(List<ExecStep> steps) throws TMDBException {
        for (int i = 0; i < steps.size() - 1; i++) {
            String fromClass = steps.get(i).className;
            String toClass = steps.get(i + 1).className;
            int fromId = memConnect.getClassId(fromClass);
            int toId = memConnect.getClassId(toClass);

            if (!hasDeputyRelation(fromId, toId)) {
                throw new TMDBException(ErrorList.CLASS_NAME_DOES_NOT_EXIST,
                        "类之间不存在代理关系: " + fromClass + " -> " + toClass);
            }
        }

        // 验证每一层的筛选条件中的属性名
        for (ExecStep step : steps) {
            if (step.filterCondition == null || step.filterCondition.isEmpty()) continue;
            int classId = memConnect.getClassId(step.className);
            validateFilterAttributes(step.filterCondition, classId);
        }
    }

    /**
     * 检查两个类之间是否存在代理关系（正向或逆向）。
     */
    private boolean hasDeputyRelation(int classId1, int classId2) {
        for (DeputyTableItem item : MemConnect.getDeputyTableList()) {
            if ((item.originid == classId1 && item.deputyid == classId2)
                    || (item.originid == classId2 && item.deputyid == classId1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 验证筛选条件中出现的属性名是否有效（包括虚属性）。
     */
    private void validateFilterAttributes(String filterCondition, int classId) throws TMDBException {
        // 提取条件中的属性名（在操作符前的标识符）
        List<String> attrNames = extractAttrNames(filterCondition);
        for (String attrName : attrNames) {
            if (!attributeExists(classId, attrName)) {
                throw new TMDBException(ErrorList.COLUMN_NAME_DOES_NOT_EXIST,
                        "类 " + memConnect.getClassName(classId) + " 中不存在属性: " + attrName);
            }
        }
    }

    /**
     * 从筛选条件字符串中提取所有属性名。
     */
    private List<String> extractAttrNames(String condition) {
        List<String> names = new ArrayList<>();
        String[] parts = condition.split("\\s+(AND|OR)\\s+");
        for (String part : parts) {
            String trimmed = part.trim();
            // 找到操作符位置
            String[] operators = {">=", "<=", "!=", "=", ">", "<"};
            for (String op : operators) {
                int idx = trimmed.indexOf(op);
                if (idx > 0) {
                    names.add(trimmed.substring(0, idx).trim());
                    break;
                }
            }
        }
        return names;
    }

    /**
     * 检查指定类中是否存在某属性（包括虚属性）。
     * 支持非限定名匹配限定名（例如 "name" 匹配 "Song.name"）。
     */
    private boolean attributeExists(int classId, String attrName) {
        // 精确匹配
        for (AttributeTableItem item : MemConnect.getAttributeTableList()) {
            if (item.classid == classId && item.attrname.equals(attrName)) {
                return true;
            }
        }
        // 后缀匹配（例如 "name" 匹配 "Song.name"）
        String suffix = "." + attrName;
        for (AttributeTableItem item : MemConnect.getAttributeTableList()) {
            if (item.classid == classId && item.attrname.endsWith(suffix)) {
                return true;
            }
        }
        // 也检查switch表（虚属性可能以其他形式存在）
        for (SwitchingTableItem item : MemConnect.getSwitchingTableList()) {
            if (item.deputyId == classId && item.deputyAttr.equals(attrName)) {
                return true;
            }
        }
        return false;
    }

    // ===== 对象获取与导航 =====

    /**
     * 获取指定类的所有未删除对象。
     */
    private List<Tuple> getClassObjects(int classId) {
        List<Tuple> result = new ArrayList<>();
        for (ObjectTableItem item : MemConnect.getObjectTableList()) {
            if (item.classid != classId) continue;
            Tuple tuple = memConnect.GetTuple(item.tupleid);
            if (tuple != null && !tuple.delete) {
                tuple.setTupleId(item.tupleid);
                tuple.classId = classId;
                result.add(tuple);
            }
        }
        return result;
    }

    /**
     * 从当前类的对象导航到目标类的关联对象。
     * 支持正向导航（源类 → 代理类）和逆向导航（代理类 → 源类）。
     */
    private List<Tuple> navigate(int currentClassId, List<Tuple> currentTuples, int targetClassId) throws TMDBException {
        List<Tuple> result = new ArrayList<>();
        boolean foundDirection = false;

        // 正向：target 是 current 的代理类
        for (DeputyTableItem deputyItem : MemConnect.getDeputyTableList()) {
            if (deputyItem.originid == currentClassId && deputyItem.deputyid == targetClassId) {
                foundDirection = true;
                for (Tuple tuple : currentTuples) {
                    for (BiPointerTableItem biItem : MemConnect.getBiPointerTableList()) {
                        if (biItem.classid == currentClassId
                                && biItem.objectid == tuple.tupleId
                                && biItem.deputyid == targetClassId) {
                            Tuple targetTuple = memConnect.GetTuple(biItem.deputyobjectid);
                            if (targetTuple != null && !targetTuple.delete) {
                                targetTuple.setTupleId(biItem.deputyobjectid);
                                targetTuple.classId = targetClassId;
                                result.add(targetTuple);
                            }
                        }
                    }
                }
                break;
            }
        }

        if (!foundDirection) {
            // 逆向：current 是 target 的代理类
            for (DeputyTableItem deputyItem : MemConnect.getDeputyTableList()) {
                if (deputyItem.originid == targetClassId && deputyItem.deputyid == currentClassId) {
                    foundDirection = true;
                    for (Tuple tuple : currentTuples) {
                        for (BiPointerTableItem biItem : MemConnect.getBiPointerTableList()) {
                            if (biItem.deputyid == currentClassId
                                    && biItem.deputyobjectid == tuple.tupleId
                                    && biItem.classid == targetClassId) {
                                Tuple targetTuple = memConnect.GetTuple(biItem.objectid);
                                if (targetTuple != null && !targetTuple.delete) {
                                    targetTuple.setTupleId(biItem.objectid);
                                    targetTuple.classId = targetClassId;
                                    result.add(targetTuple);
                                }
                            }
                        }
                    }
                    break;
                }
            }
        }

        if (!foundDirection) {
            throw new TMDBException(ErrorList.CLASS_NAME_DOES_NOT_EXIST,
                    "类之间不存在代理关系: " + memConnect.getClassName(currentClassId)
                            + " -> " + memConnect.getClassName(targetClassId));
        }

        return deduplicateTuples(result);
    }

    /**
     * 对元组列表去重（基于 tupleId 和 classId）。
     */
    private List<Tuple> deduplicateTuples(List<Tuple> tuples) {
        Map<String, Tuple> uniqueMap = new HashMap<>();
        for (Tuple tuple : tuples) {
            String key = tuple.classId + "_" + tuple.tupleId;
            uniqueMap.put(key, tuple);
        }
        return new ArrayList<>(uniqueMap.values());
    }

    // ===== 复合筛选条件 =====

    /**
     * 应用复合筛选条件（支持 AND/OR）。
     * 格式: attr > 5 AND attr2 = 'val' OR attr3 != 10
     * 优先级：AND 高于 OR（从左到右处理）
     */
    private List<Tuple> applyCompoundFilter(List<Tuple> tuples, int classId, String filterCondition) throws TMDBException {
        if (tuples == null || tuples.isEmpty() || filterCondition == null || filterCondition.isEmpty()) {
            return tuples;
        }

        // 按 OR 分割（最低优先级）
        String[] orParts = filterCondition.split("\\s+OR\\s+");
        if (orParts.length > 1) {
            List<Tuple> result = new ArrayList<>();
            for (String orPart : orParts) {
                List<Tuple> orResult = applyAndFilter(tuples, classId, orPart.trim());
                for (Tuple t : orResult) {
                    if (!result.contains(t)) {
                        result.add(t);
                    }
                }
            }
            return result;
        }

        return applyAndFilter(tuples, classId, filterCondition);
    }

    /**
     * 应用 AND 筛选（所有条件必须同时满足）。
     */
    private List<Tuple> applyAndFilter(List<Tuple> tuples, int classId, String condition) throws TMDBException {
        String[] andParts = condition.split("\\s+AND\\s+");
        List<Tuple> result = new ArrayList<>(tuples);

        for (String andPart : andParts) {
            String trimmed = andPart.trim();
            if (trimmed.isEmpty()) continue;
            FilterCondition fc = parseFilterCondition(trimmed, classId);
            List<Tuple> filtered = new ArrayList<>();
            for (Tuple tuple : result) {
                if (evaluateCondition(tuple, fc)) {
                    filtered.add(tuple);
                }
            }
            result = filtered;
            if (result.isEmpty()) break;
        }

        return result;
    }

    /**
     * 解析单个过滤条件。
     * 支持: attr op value 格式，其中 op ∈ {>=, <=, !=, =, >, <}
     */
    private FilterCondition parseFilterCondition(String condition, int classId) throws TMDBException {
        String[] operators = {">=", "<=", "!=", "=", ">", "<"};
        String attrName = null;
        String operator = null;
        String value = null;

        for (String op : operators) {
            int opIndex = findOperatorIndex(condition, op);
            if (opIndex != -1) {
                attrName = condition.substring(0, opIndex).trim();
                operator = op;
                value = condition.substring(opIndex + op.length()).trim();
                break;
            }
        }

        if (attrName == null || operator == null) {
            throw new TMDBException(ErrorList.TYPE_IS_NOT_SUPPORTED,
                    "无法解析过滤条件: " + condition);
        }

        // 去除值的引号
        if ((value.startsWith("'") && value.endsWith("'"))
                || (value.startsWith("\"") && value.endsWith("\""))) {
            value = value.substring(1, value.length() - 1);
        }

        // 解析虚属性：通过 switch 表找到实属性
        int attrId = resolveAttrId(classId, attrName);
        String attrType = resolveAttrType(classId, attrName);

        FilterCondition result = new FilterCondition();
        result.attrName = attrName;
        result.attrId = attrId;
        result.operator = operator;
        result.value = value;
        result.attrType = attrType;

        return result;
    }

    /**
     * 查找操作符在条件字符串中的位置（跳过引号内的内容）。
     */
    private int findOperatorIndex(String condition, String operator) {
        int index = condition.indexOf(operator);
        if (index == -1) return -1;

        int quoteCount = 0;
        for (int i = 0; i < index; i++) {
            if (condition.charAt(i) == '\'') quoteCount++;
        }
        if (quoteCount % 2 == 1) {
            return condition.indexOf(operator, index + operator.length());
        }
        return index;
    }

    /**
     * 解析属性ID，优先从 attribute 表查，其次从 switch 表查虚属性。
     * 支持非限定名匹配限定名（例如 "name" 匹配 "Song.name"）。
     */
    private int resolveAttrId(int classId, String attrName) throws TMDBException {
        // 先从 attribute 表精确匹配
        for (AttributeTableItem item : MemConnect.getAttributeTableList()) {
            if (item.classid == classId && item.attrname.equals(attrName)) {
                return item.attrid;
            }
        }
        // 后缀匹配（例如 "name" 匹配 "Song.name"）
        String suffix = "." + attrName;
        for (AttributeTableItem item : MemConnect.getAttributeTableList()) {
            if (item.classid == classId && item.attrname.endsWith(suffix)) {
                return item.attrid;
            }
        }
        // 再从 switch 表查虚属性
        for (SwitchingTableItem item : MemConnect.getSwitchingTableList()) {
            if (item.deputyId == classId && item.deputyAttr.equals(attrName)) {
                return item.deputyAttrId;
            }
        }
        throw new TMDBException(ErrorList.COLUMN_NAME_DOES_NOT_EXIST,
                memConnect.getClassName(classId) + "." + attrName);
    }

    /**
     * 解析属性类型。支持非限定名匹配限定名（例如 "name" 匹配 "Song.name"）。
     */
    private String resolveAttrType(int classId, String attrName) throws TMDBException {
        // 精确匹配
        for (AttributeTableItem item : MemConnect.getAttributeTableList()) {
            if (item.classid == classId && item.attrname.equals(attrName)) {
                return item.attrtype;
            }
        }
        // 后缀匹配（例如 "name" 匹配 "Song.name"）
        String suffix = "." + attrName;
        for (AttributeTableItem item : MemConnect.getAttributeTableList()) {
            if (item.classid == classId && item.attrname.endsWith(suffix)) {
                return item.attrtype;
            }
        }
        for (SwitchingTableItem item : MemConnect.getSwitchingTableList()) {
            if (item.deputyId == classId && item.deputyAttr.equals(attrName)) {
                // 虚属性的类型来源于其源属性
                for (AttributeTableItem attrItem : MemConnect.getAttributeTableList()) {
                    if (attrItem.classid == item.oriId && attrItem.attrid == item.oriAttrid) {
                        return attrItem.attrtype;
                    }
                }
            }
        }
        throw new TMDBException(ErrorList.COLUMN_NAME_DOES_NOT_EXIST, attrName);
    }

    /**
     * 评估单个元组是否满足条件。
     */
    private boolean evaluateCondition(Tuple tuple, FilterCondition condition) {
        if (tuple.tuple == null || condition.attrId >= tuple.tuple.length) {
            return false;
        }

        Object tupleValue = tuple.tuple[condition.attrId];
        if (tupleValue == null) {
            return condition.operator.equals("!=");
        }

        String tupleStr = stripQuotes(tupleValue.toString());

        int cmp;
        try {
            double tupleNum = Double.parseDouble(tupleStr);
            double condNum = Double.parseDouble(condition.value);
            cmp = Double.compare(tupleNum, condNum);
        } catch (NumberFormatException e) {
            cmp = tupleStr.compareTo(condition.value);
        }

        switch (condition.operator) {
            case "=":  return cmp == 0;
            case "!=": return cmp != 0;
            case ">":  return cmp > 0;
            case "<":  return cmp < 0;
            case ">=": return cmp >= 0;
            case "<=": return cmp <= 0;
            default:   return false;
        }
    }

    /**
     * 去除字符串两端的引号（如果存在）。
     */
    private String stripQuotes(String s) {
        if (s == null || s.length() < 2) return s;
        char first = s.charAt(0);
        char last = s.charAt(s.length() - 1);
        if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // ===== 结果构建 =====

    /**
     * 从目标类对象中提取目标属性，构建 SelectResult。
     *
     * @param tuples     目标类对象列表
     * @param classId    目标类ID
     * @param targetAttr 目标属性名（为 null 表示返回所有属性）
     */
    private SelectResult buildResult(List<Tuple> tuples, int classId, String targetAttr) throws TMDBException {
        String className = memConnect.getClassName(classId);

        if (targetAttr != null && !targetAttr.isEmpty()) {
            return buildSingleAttrResult(tuples, classId, className, targetAttr);
        } else {
            return buildAllAttrResult(tuples, classId, className);
        }
    }

    /**
     * 构建单属性的查询结果。
     */
    private SelectResult buildSingleAttrResult(List<Tuple> tuples, int classId, String className, String targetAttr) throws TMDBException {
        SelectResult selectResult = new SelectResult(1);
        int attrId = resolveAttrId(classId, targetAttr);
        String attrType = resolveAttrType(classId, targetAttr);

        selectResult.getClassName()[0] = className;
        selectResult.getAttrname()[0] = targetAttr;
        selectResult.getAlias()[0] = targetAttr;
        selectResult.getAttrid()[0] = attrId;
        selectResult.getType()[0] = attrType;

        TupleList tupleList = new TupleList();
        for (Tuple tuple : tuples) {
            Tuple newTuple = new Tuple();
            newTuple.classId = classId;
            newTuple.tupleId = tuple.tupleId;
            newTuple.tuple = new Object[1];
            newTuple.tupleIds = new int[1];

            if (attrId < tuple.tuple.length) {
                newTuple.tuple[0] = tuple.tuple[attrId];
                newTuple.tupleIds[0] = tuple.tupleId;
            }

            tupleList.addTuple(newTuple);
        }
        selectResult.setTpl(tupleList);
        return selectResult;
    }

    /**
     * 构建所有属性的查询结果（SELECT ... -> ClassName.* 或路径末尾无目标属性）。
     */
    private SelectResult buildAllAttrResult(List<Tuple> tuples, int classId, String className) {
        List<AttributeTableItem> attrItems = new ArrayList<>();
        for (AttributeTableItem item : MemConnect.getAttributeTableList()) {
            if (item.classid == classId) {
                attrItems.add(item);
            }
        }

        int attrCount = attrItems.size();
        SelectResult selectResult = new SelectResult(attrCount);

        TupleList tupleList = new TupleList();
        for (Tuple tuple : tuples) {
            Tuple newTuple = new Tuple();
            newTuple.classId = classId;
            newTuple.tupleId = tuple.tupleId;
            newTuple.tuple = new Object[attrCount];
            newTuple.tupleIds = new int[attrCount];

            for (int i = 0; i < attrCount; i++) {
                AttributeTableItem attrItem = attrItems.get(i);
                selectResult.getClassName()[i] = className;
                selectResult.getAttrname()[i] = attrItem.attrname;
                selectResult.getAlias()[i] = attrItem.attrname;
                selectResult.getAttrid()[i] = attrItem.attrid;
                selectResult.getType()[i] = attrItem.attrtype;

                if (attrItem.attrid < tuple.tuple.length) {
                    newTuple.tuple[i] = tuple.tuple[attrItem.attrid];
                }
                newTuple.tupleIds[i] = tuple.tupleId;
            }
            tupleList.addTuple(newTuple);
        }
        selectResult.setTpl(tupleList);
        return selectResult;
    }

    // ===== 内部数据类 =====

    private static class ExecStep {
        public String className;
        public String filterCondition;
    }

    private static class FilterCondition {
        public String attrName;
        public int attrId;
        public String operator;
        public String value;
        public String attrType;
    }
}
