package edu.whu.tmdb.storage.memory.SystemTable;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class DeputyRuleTable implements Serializable {
    public List<DeputyRuleTableItem> deputyRuleTableList = new ArrayList<>();
    public int maxruleid = 0;

    public void clear(){
        deputyRuleTableList.clear();
        maxruleid = 0;
    }
}