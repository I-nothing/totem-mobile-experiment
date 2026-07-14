package edu.whu.tmdb.storage.memory.SystemTable;

import java.io.Serializable;
import java.util.Objects;

public class AttributeTableItem implements Serializable {
    public int classid = 0;             // 类id
    public int attrid = 0;              // 当前属性id
    public String attrname = "";        // 当前属性名
    public String attrtype = "";        // 当前属性类型
    public String alias = "";
    public int isVirtual = 0;           // 是否为虚属性

    public AttributeTableItem(int classid, int attrid, String attrname, String attrtype, String alias) {
        this.classid = classid;
        this.attrname = attrname;
        this.attrtype = attrtype;
        this.attrid = attrid;
        this.alias = alias;
    }
    public AttributeTableItem(){}

    public AttributeTableItem getCopy(){
        return new AttributeTableItem(this.classid, this.attrid, this.attrname, this.attrtype, this.alias);
    }

    @Override
    public boolean equals(Object object){
        if (this == object) { return true; }
        if (!(object instanceof AttributeTableItem)) {
            return false;
        }

        AttributeTableItem oi = (AttributeTableItem) object;
        if(this.classid!=oi.classid){
            return false;
        }
        if(this.attrid!=oi.attrid){
            return false;
        }
        if(this.attrname!=oi.attrname){
            return false;
        }
        if(this.attrtype!=oi.attrtype){
            return false;
        }
        if(this.alias!=oi.alias){
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + Objects.hash(this.classid)
                +Objects.hash(this.alias)
                +Objects.hash(this.attrid)
                +Objects.hash(this.attrname)
                +Objects.hash(this.attrtype)
        ;
        return result;
    }

}
