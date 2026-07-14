package edu.whu.tmdb.storage.memory.SystemTable;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;

public class DeputyRuleTableItem implements Serializable {
    public int ruleid = 0;           // 代理类id
    public String[] deputyrule = new String[0];    // 代理规则


    public DeputyRuleTableItem() {}

    public DeputyRuleTableItem(int ruleid, String[] deputyrule) {
        this.ruleid = ruleid;               // 源类id
        this.deputyrule = deputyrule;       // 代理规则
    }

    /**
     * 给定对象，判断是否与此代理对象相等
     */
    @Override
    public boolean equals(Object object){
        if (this == object) { return true; }
        if (!(object instanceof DeputyRuleTableItem)) {
            return false;
        }
        DeputyRuleTableItem oi = (DeputyRuleTableItem) object;
        if(this.ruleid != oi.ruleid){
            return false;
        }
        if(this.deputyrule != oi.deputyrule){
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + Objects.hash(this.ruleid) + Objects.hash(Arrays.stream(this.deputyrule).toArray());
        return result;
    }
}
