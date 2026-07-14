package edu.whu.tmdb.storage.memory.SystemTable;

import java.util.ArrayList;
import java.util.List;

public class AttributeTable {
    public List<AttributeTableItem> attribuleTableList = new ArrayList<>();

    public void clear() {
        attribuleTableList.clear();
    }
}
