package edu.whu.tmdb.query.operations.impl;

import edu.whu.tmdb.query.operations.utils.SelectResult;
import edu.whu.tmdb.storage.memory.Tuple;
import edu.whu.tmdb.storage.memory.TupleList;
import net.sf.jsqlparser.statement.select.OrderByElement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class OrderBy {
    
    public SelectResult orderBy(List<OrderByElement> orderByElements, SelectResult selectResult) {
        if (orderByElements == null || orderByElements.isEmpty()) {
            return selectResult;
        }

        // 获取排序列在结果中的索引
        OrderByElement orderByElement = orderByElements.get(0); // 目前只处理第一个排序条件
        String columnName = orderByElement.getExpression().toString();
        int columnIndex = -1;
        
        // 查找排序列的索引
        for (int i = 0; i < selectResult.getAttrname().length; i++) {
            if (selectResult.getAttrname()[i].equals(columnName)) {
                columnIndex = i;
                break;
            }
        }
        
        if (columnIndex == -1) {
            return selectResult; // 如果找不到列，返回原结果
        }

        // 对元组列表进行排序
        TupleList tpl = selectResult.getTpl();
        List<Tuple> sortedList = new ArrayList<>(tpl.tuplelist);
        final int sortColumnIndex = columnIndex;
        
        boolean ascending = orderByElement.isAsc();
        sortedList.sort((t1, t2) -> {
            Object v1 = t1.tuple[sortColumnIndex];
            Object v2 = t2.tuple[sortColumnIndex];

            if (v1 == null && v2 == null) return 0;
            if (v1 == null) return ascending ? -1 : 1;
            if (v2 == null) return ascending ? 1 : -1;

            int cmp;
            try {
                double d1 = Double.parseDouble(v1.toString().trim());
                double d2 = Double.parseDouble(v2.toString().trim());
                cmp = Double.compare(d1, d2);
            } catch (NumberFormatException e) {
                cmp = v1.toString().compareTo(v2.toString());
            }
            return ascending ? cmp : -cmp;
        });

        // 创建新的 TupleList
        TupleList sortedTpl = new TupleList();
        sortedTpl.tuplelist = sortedList;
        sortedTpl.tuplenum = tpl.tuplenum;
        
        // 更新 SelectResult
        selectResult.setTpl(sortedTpl);
        return selectResult;
    }
}
